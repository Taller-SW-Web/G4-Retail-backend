package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static java.math.BigDecimal.ONE;

/**
 * Tests unitarios de {@link VentaService} — F5 (RF-13, RF-14, RF-15).
 *
 * <p>No usa Spring context: instancia el servicio directamente para mayor velocidad.
 * Cubre los tres ejes del examen parcial:</p>
 * <ol>
 *   <li>Calculo fiscal correcto (IGV 18 %).</li>
 *   <li>Calculo de vuelto en pago efectivo.</li>
 *   <li>Idempotencia: misma llave = misma respuesta.</li>
 *   <li>Normativa SUNAT: boleta &ge; S/700 requiere DNI; factura requiere RUC.</li>
 * </ol>
 */
@DisplayName("VentaService — F5 Tests Unitarios")
class VentaServiceTest {

    private VentaService servicio;

    @BeforeEach
    void setUp() {
        servicio = new VentaService();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private VentaRequest.ItemVentaDto item(String sku, int cantidad, String precio) {
        return new VentaRequest.ItemVentaDto(sku, cantidad, new BigDecimal(precio));
    }

    private VentaRequest.PagoDto pagoEfectivo(String monto) {
        return new VentaRequest.PagoDto("EFECTIVO", new BigDecimal(monto), null);
    }

    private VentaRequest.PagoDto pagoTarjeta(String monto) {
        return new VentaRequest.PagoDto("TARJETA_POS", new BigDecimal(monto), "OP-12345");
    }

    private VentaRequest.ComprobanteDto boleta(String doc) {
        return new VentaRequest.ComprobanteDto("BOLETA", doc, "Cliente Test");
    }

    private VentaRequest.ComprobanteDto factura(String ruc) {
        return new VentaRequest.ComprobanteDto("FACTURA", ruc, "Empresa SAC");
    }

    private VentaRequest request(List<VentaRequest.ItemVentaDto> items,
                                  VentaRequest.PagoDto pago,
                                  VentaRequest.ComprobanteDto comprobante) {
        return new VentaRequest(
                "RETAIL",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                items,
                pago,
                comprobante,
                false
        );
    }

    // ── Tests de calculo fiscal ──────────────────────────────────────────────

    @Nested
    @DisplayName("Calculo fiscal IGV 18 %")
    class CalculoFiscal {

        @Test
        @DisplayName("calcularTotal suma cantidad × precioFinal de todos los items")
        void totalEsSumaDeItems() {
            // 2 × S/59.90 + 1 × S/120.00 = S/239.80
            VentaRequest req = request(
                    List.of(item("SKU-001", 2, "59.90"), item("SKU-002", 1, "120.00")),
                    pagoEfectivo("239.80"),
                    boleta("12345678")
            );

            BigDecimal total = servicio.calcularTotal(req);

            assertThat(total).isEqualByComparingTo("239.80");
        }

        @Test
        @DisplayName("El comprobante emitido tiene subtotal + igv = total con IGV 18 %")
        void descomposicionFiscalCorrecta() {
            // Total S/118.00 → subtotal = 100.00, IGV = 18.00
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("118.00"),
                    boleta("12345678")
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            BigDecimal subtotal = respuesta.comprobante().subtotal();
            BigDecimal igv = respuesta.comprobante().igv();
            BigDecimal total = respuesta.comprobante().total();

            assertThat(subtotal).isEqualByComparingTo("100.00");
            assertThat(igv).isEqualByComparingTo("18.00");
            assertThat(subtotal.add(igv)).isEqualByComparingTo(total);
        }

        @Test
        @DisplayName("IGV = total × 0.18 / 1.18 redondeado a 2 decimales")
        void igvRedondeadoCorrectamente() {
            // Total S/59.90 → IGV = 59.90 × 0.18 / 1.18 = 9.137... → 9.14
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "59.90")),
                    pagoEfectivo("59.90"),
                    boleta("12345678")
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.comprobante().igv()).isEqualByComparingTo("9.14");
        }
    }

    // ── Tests de validacion de monto ─────────────────────────────────────────

    @Nested
    @DisplayName("Validacion del monto de pago")
    class ValidacionMonto {

        @Test
        @DisplayName("Monto declarado exacto pasa la validacion sin excepcion")
        void montoExactoEsValido() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 2, "50.00")),
                    pagoEfectivo("100.00"),
                    boleta("12345678")
            );

            // No debe lanzar excepcion
            servicio.validarMontoPago(req, new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("Diferencia mayor a tolerancia lanza ReglaNegocioException MONTO_INCONSISTENTE")
        void diferenciaMayorAToleranciaCausaExcepcion() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "100.00")),
                    pagoEfectivo("200.00"),   // declara S/200 pero el total real es S/100
                    boleta("12345678")
            );

            assertThatThrownBy(() -> servicio.validarMontoPago(req, new BigDecimal("100.00")))
                    .isInstanceOf(ReglaNegocioException.class)
                    .hasMessageContaining("100")
                    .extracting(e -> ((ReglaNegocioException) e).getCodigo())
                    .isEqualTo("MONTO_INCONSISTENTE");
        }
    }

    // ── Tests de normativa SUNAT ─────────────────────────────────────────────

    @Nested
    @DisplayName("Normativa SUNAT")
    class NormativaSunat {

        @Test
        @DisplayName("Boleta menor a S/700 sin DNI pasa sin excepcion")
        void boletaSinDniMenorA700EsValida() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "100.00")),
                    pagoEfectivo("100.00"),
                    boleta(null)   // sin documento
            );

            // No debe lanzar excepcion
            servicio.validarNormativaSunat(req, new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("Boleta >= S/700 sin DNI lanza BOLETA_REQUIERE_DNI")
        void boletaMayorA700SinDniCausaExcepcion() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "700.00")),
                    pagoEfectivo("700.00"),
                    boleta(null)   // sin DNI
            );

            assertThatThrownBy(() -> servicio.validarNormativaSunat(req, new BigDecimal("700.00")))
                    .isInstanceOf(ReglaNegocioException.class)
                    .extracting(e -> ((ReglaNegocioException) e).getCodigo())
                    .isEqualTo("BOLETA_REQUIERE_DNI");
        }

        @Test
        @DisplayName("Boleta >= S/700 con DNI valido de 8 digitos pasa sin excepcion")
        void boletaMayorA700ConDniEsValida() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "700.00")),
                    pagoEfectivo("700.00"),
                    boleta("12345678")
            );

            // No debe lanzar excepcion
            servicio.validarNormativaSunat(req, new BigDecimal("700.00"));
        }

        @Test
        @DisplayName("Factura sin RUC de 11 digitos lanza FACTURA_REQUIERE_RUC")
        void facturaSinRucCausaExcepcion() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "500.00")),
                    pagoEfectivo("500.00"),
                    factura("12345678")   // solo 8 digitos, no es RUC
            );

            assertThatThrownBy(() -> servicio.validarNormativaSunat(req, new BigDecimal("500.00")))
                    .isInstanceOf(ReglaNegocioException.class)
                    .extracting(e -> ((ReglaNegocioException) e).getCodigo())
                    .isEqualTo("FACTURA_REQUIERE_RUC");
        }
    }

    // ── Tests de idempotencia ────────────────────────────────────────────────

    @Nested
    @DisplayName("Idempotencia")
    class Idempotencia {

        @Test
        @DisplayName("Misma Idempotency-Key devuelve exactamente el mismo pedidoId")
        void mismaLlaveDevuelveMismoPedidoId() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("118.00"),
                    boleta("12345678")
            );
            String llave = UUID.randomUUID().toString();

            VentaResponse primera = servicio.registrarVenta(req, llave);
            VentaResponse segunda = servicio.registrarVenta(req, llave);

            assertThat(primera.pedidoId()).isEqualTo(segunda.pedidoId());
            assertThat(primera.comprobante().correlativo())
                    .isEqualTo(segunda.comprobante().correlativo());
        }

        @Test
        @DisplayName("Llaves distintas generan pedidoIds distintos")
        void diferentesLlavesGeneranPedidosDistintos() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("118.00"),
                    boleta("12345678")
            );

            VentaResponse primera = servicio.registrarVenta(req, UUID.randomUUID().toString());
            VentaResponse segunda = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(primera.pedidoId()).isNotEqualTo(segunda.pedidoId());
        }
    }

    // ── Tests de ticket regalo ───────────────────────────────────────────────

    @Nested
    @DisplayName("Ticket de regalo")
    class TicketRegalo {

        @Test
        @DisplayName("Sin emitirTicketRegalo el campo ticketRegalo es null")
        void sinRegaloTicketEsNull() {
            VentaRequest req = new VentaRequest(
                    "RETAIL", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("118.00"),
                    boleta("12345678"),
                    false   // <-- sin regalo
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.ticketRegalo()).isNull();
        }

        @Test
        @DisplayName("Con emitirTicketRegalo=true el ticket tiene codigoCanje no vacio")
        void conRegaloTicketTieneCodigoCanje() {
            VentaRequest req = new VentaRequest(
                    "RETAIL", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("118.00"),
                    boleta("12345678"),
                    true   // <-- con regalo
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.ticketRegalo()).isNotNull();
            assertThat(respuesta.ticketRegalo().codigoCanje()).isNotBlank();
            assertThat(respuesta.ticketRegalo().fechaLimiteCambio()).isNotNull();
        }
    }
}

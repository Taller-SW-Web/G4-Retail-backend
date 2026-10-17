package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import pe.edu.unmsm.fisi.retail.client.VentasClient;
import pe.edu.unmsm.fisi.retail.client.dto.VentasPedidoResponse;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitarios de {@link VentaService} — F5 (RF-13, RF-14, RF-15).
 *
 * <p>No usa Spring context: instancia el servicio directamente para mayor velocidad.
 * Cubre los ejes del examen parcial:</p>
 * <ol>
 *   <li>Calculo fiscal correcto (IGV 18 %).</li>
 *   <li>Calculo de vuelto en pago efectivo (montoRecibidoEfectivo - total).</li>
 *   <li>Pago con tarjeta (vuelto = 0, referencia obligatoria).</li>
 *   <li>Idempotencia: misma llave = misma respuesta.</li>
 *   <li>Normativa SUNAT: boleta &ge; S/700 requiere DNI; factura requiere RUC.</li>
 *   <li>Validacion de turno de caja abierto.</li>
 *   <li>Estructura de respuesta alineada con el contrato v0.4 §5.5.</li>
 * </ol>
 */
@DisplayName("VentaService — F5 Tests Unitarios")
class VentaServiceTest {

    private VentaService servicio;

    /** Turno mock que se abre en cada test para que las ventas puedan procesarse. */
    private static final String TURNO_ABIERTO = "turno-test-001";

    @BeforeEach
    void setUp() {
        servicio = new VentaService();
        servicio.abrirTurnoMock(TURNO_ABIERTO);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private VentaRequest.ItemVentaDto item(String sku, int cantidad, String precio) {
        return new VentaRequest.ItemVentaDto(sku, cantidad, new BigDecimal(precio));
    }

    private VentaRequest.PagoDto pagoEfectivo(String montoRecibido) {
        return new VentaRequest.PagoDto("EFECTIVO", new BigDecimal(montoRecibido), null, null);
    }

    private VentaRequest.PagoDto pagoTarjeta() {
        return new VentaRequest.PagoDto("TARJETA_POS", null, null, "OP-12345");
    }

    private VentaRequest.ComprobanteDto boleta(String doc) {
        return new VentaRequest.ComprobanteDto("BOLETA", doc, "Cliente Test");
    }

    private VentaRequest.ComprobanteDto factura(String ruc) {
        return new VentaRequest.ComprobanteDto("FACTURA", ruc, "Empresa SAC");
    }

    /** Crea un request con turno abierto y datos basicos para los tests. */
    private VentaRequest request(List<VentaRequest.ItemVentaDto> items,
                                  VentaRequest.PagoDto pago,
                                  VentaRequest.ComprobanteDto comprobante) {
        return new VentaRequest(
                "POS-01",
                TURNO_ABIERTO,
                "cliente-test-001",
                items,
                pago,
                comprobante,
                false,
                null,
                null
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

    // ── Tests de vuelto y pago ───────────────────────────────────────────────

    @Nested
    @DisplayName("Calculo de vuelto y validacion de pago")
    class CalculoVuelto {

        @Test
        @DisplayName("Pago en efectivo con monto exacto produce vuelto = 0")
        void pagoExactoVueltoCero() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "100.00")),
                    pagoEfectivo("100.00"),
                    boleta("12345678")
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.pago().medioPago()).isEqualTo("EFECTIVO");
            assertThat(respuesta.pago().montoRecibido()).isEqualByComparingTo("100.00");
            assertThat(respuesta.pago().vuelto()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("Pago en efectivo con excedente calcula vuelto = montoRecibido - total")
        void pagoConExcedenteCalculaVuelto() {
            // Total = S/83.50, paga con S/100.00, vuelto = S/16.50
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "83.50")),
                    pagoEfectivo("100.00"),
                    boleta("12345678")
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.pago().montoRecibido()).isEqualByComparingTo("100.00");
            assertThat(respuesta.pago().vuelto()).isEqualByComparingTo("16.50");
        }

        @Test
        @DisplayName("Pago en efectivo con billete grande calcula vuelto correcto")
        void pagoConBilleteDe200() {
            // Total = S/118.00, paga con S/200.00, vuelto = S/82.00
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("200.00"),
                    boleta("12345678")
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.pago().montoRecibido()).isEqualByComparingTo("200.00");
            assertThat(respuesta.pago().vuelto()).isEqualByComparingTo("82.00");
        }

        @Test
        @DisplayName("Pago en efectivo insuficiente lanza MONTO_INSUFICIENTE")
        void pagoInsuficienteCausaExcepcion() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "200.00")),
                    pagoEfectivo("150.00"),
                    boleta("12345678")
            );

            assertThatThrownBy(() -> servicio.registrarVenta(req, UUID.randomUUID().toString()))
                    .isInstanceOf(ReglaNegocioException.class)
                    .hasMessageContaining("150")
                    .extracting(e -> ((ReglaNegocioException) e).getCodigo())
                    .isEqualTo("MONTO_INSUFICIENTE");
        }

        @Test
        @DisplayName("Pago con tarjeta POS tiene vuelto = 0 y monto = total")
        void pagoTarjetaVueltoCero() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "100.00")),
                    pagoTarjeta(),
                    boleta("12345678")
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.pago().medioPago()).isEqualTo("TARJETA_POS");
            assertThat(respuesta.pago().montoRecibido()).isEqualByComparingTo("100.00");
            assertThat(respuesta.pago().vuelto()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("Pago con tarjeta sin referencia lanza REFERENCIA_VOUCHER_INVALIDA")
        void tarjetaSinReferenciaCausaExcepcion() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "100.00")),
                    new VentaRequest.PagoDto("TARJETA_POS", null, null, null),
                    boleta("12345678")
            );

            assertThatThrownBy(() -> servicio.registrarVenta(req, UUID.randomUUID().toString()))
                    .isInstanceOf(ReglaNegocioException.class)
                    .extracting(e -> ((ReglaNegocioException) e).getCodigo())
                    .isEqualTo("REFERENCIA_VOUCHER_INVALIDA");
        }

        @Test
        @DisplayName("Pago con tarjeta con referencia corta (< 4 chars) lanza REFERENCIA_VOUCHER_INVALIDA")
        void tarjetaReferenciaCorta() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "100.00")),
                    new VentaRequest.PagoDto("TARJETA_POS", null, null, "AB"),
                    boleta("12345678")
            );

            assertThatThrownBy(() -> servicio.registrarVenta(req, UUID.randomUUID().toString()))
                    .isInstanceOf(ReglaNegocioException.class)
                    .extracting(e -> ((ReglaNegocioException) e).getCodigo())
                    .isEqualTo("REFERENCIA_VOUCHER_INVALIDA");
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
                    "POS-01", TURNO_ABIERTO, "cliente-test-001",
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("118.00"),
                    boleta("12345678"),
                    false,   // <-- sin regalo
                    null, null
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.ticketRegalo()).isNull();
        }

        @Test
        @DisplayName("Con emitirTicketRegalo=true el ticket tiene codigoCanje no vacio")
        void conRegaloTicketTieneCodigoCanje() {
            VentaRequest req = new VentaRequest(
                    "POS-01", TURNO_ABIERTO, "cliente-test-001",
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("118.00"),
                    boleta("12345678"),
                    true,   // <-- con regalo
                    null, null
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.ticketRegalo()).isNotNull();
            assertThat(respuesta.ticketRegalo().codigoCanje()).isNotBlank();
            assertThat(respuesta.ticketRegalo().fechaLimiteCambio()).isNotNull();
        }
    }

    // ── Tests de turno de caja ───────────────────────────────────────────────

    @Nested
    @DisplayName("Validacion de turno de caja")
    class ValidacionTurno {

        @Test
        @DisplayName("Turno no registrado como abierto lanza TURNO_NO_ABIERTO")
        void turnoNoAbiertoLanzaExcepcion() {
            VentaRequest req = new VentaRequest(
                    "POS-01", "turno-inexistente", "cliente-test-001",
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("118.00"),
                    boleta("12345678"),
                    false, null, null
            );

            assertThatThrownBy(() -> servicio.registrarVenta(req, UUID.randomUUID().toString()))
                    .isInstanceOf(ReglaNegocioException.class)
                    .hasMessageContaining("turno-inexistente")
                    .extracting(e -> ((ReglaNegocioException) e).getCodigo())
                    .isEqualTo("TURNO_NO_ABIERTO");
        }

        @Test
        @DisplayName("Turno abierto permite registrar la venta correctamente")
        void turnoAbiertoPermiteVenta() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("118.00"),
                    boleta("12345678")
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.pedidoId()).isNotBlank();
            assertThat(respuesta.estadoPedido()).isEqualTo("PAGADO");
        }
    }

    // ── Tests de estructura de respuesta (contrato v0.4) ────────────────────

    @Nested
    @DisplayName("Estructura de respuesta (contrato v0.4 §5.5)")
    class EstructuraRespuesta {

        @Test
        @DisplayName("La respuesta incluye ventaId, pedidoId, estadoPedido, pago con vuelto y comprobante con QR")
        void respuestaTieneCamposDelContrato() {
            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("200.00"),
                    boleta("12345678")
            );

            VentaResponse respuesta = servicio.registrarVenta(req, UUID.randomUUID().toString());

            // Campos de cabecera
            assertThat(respuesta.ventaId()).isNotBlank();
            assertThat(respuesta.pedidoId()).startsWith("VNT-");
            assertThat(respuesta.estadoPedido()).isEqualTo("PAGADO");

            // Pago con vuelto correcto
            assertThat(respuesta.pago()).isNotNull();
            assertThat(respuesta.pago().medioPago()).isEqualTo("EFECTIVO");
            assertThat(respuesta.pago().montoRecibido()).isEqualByComparingTo("200.00");
            assertThat(respuesta.pago().vuelto()).isEqualByComparingTo("82.00");

            // Comprobante con tipo y QR
            assertThat(respuesta.comprobante().tipo()).isEqualTo("BOLETA");
            assertThat(respuesta.comprobante().serie()).isEqualTo("B001");
            assertThat(respuesta.comprobante().qr()).contains("B001");
            assertThat(respuesta.comprobante().qr()).contains("20123456789"); // RUC empresa
            assertThat(respuesta.comprobante().fechaEmision()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Integración con Módulo de Ventas G5 (Hito 3)")
    class IntegracionVentasG5 {

        @Test
        @DisplayName("Cuando VentasClient retorna pedidoId de Ventas, VentaService adopta dicho ID oficial")
        void adoptaPedidoIdDeVentasG5() {
            VentasClient ventasClientMock = mock(VentasClient.class);
            VentasPedidoResponse responseG5 = new VentasPedidoResponse(
                    "PED-2026-00091",
                    "PAGADO",
                    new BigDecimal("118.00"),
                    "PEN",
                    "RETAIL",
                    OffsetDateTime.now()
            );

            when(ventasClientMock.crearPedido(any(), any())).thenReturn(Optional.of(responseG5));

            VentaService servicioConVentas = new VentaService(ventasClientMock);
            servicioConVentas.abrirTurnoMock(TURNO_ABIERTO);

            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("200.00"),
                    boleta("12345678")
            );

            VentaResponse respuesta = servicioConVentas.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.pedidoId()).isEqualTo("PED-2026-00091");
            assertThat(respuesta.estadoPedido()).isEqualTo("PAGADO");
        }

        @Test
        @DisplayName("Cuando VentasClient falla o retorna vacío, VentaService aplica fallback local sin interrumpir la venta")
        void aplicaFallbackLocalAnteFalloDeVentas() {
            VentasClient ventasClientMock = mock(VentasClient.class);
            when(ventasClientMock.crearPedido(any(), any())).thenReturn(Optional.empty());

            VentaService servicioConVentas = new VentaService(ventasClientMock);
            servicioConVentas.abrirTurnoMock(TURNO_ABIERTO);

            VentaRequest req = request(
                    List.of(item("SKU-001", 1, "118.00")),
                    pagoEfectivo("200.00"),
                    boleta("12345678")
            );

            VentaResponse respuesta = servicioConVentas.registrarVenta(req, UUID.randomUUID().toString());

            assertThat(respuesta.pedidoId()).startsWith("VNT-");
            assertThat(respuesta.estadoPedido()).isEqualTo("PAGADO");
        }
    }
}


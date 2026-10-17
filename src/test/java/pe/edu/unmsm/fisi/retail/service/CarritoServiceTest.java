package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoResponse;
import pe.edu.unmsm.fisi.retail.dto.CarritoEsperaDto;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitarios de {@link CarritoService} — F4 (RF-10, RF-11, RF-12).
 *
 * <p>Verifica el cumplimiento de:</p>
 * <ul>
 *   <li>Fórmulas tributarias SUNAT: Subtotal, Descuentos, Base Imponible (total / 1.18) e IGV (18%) con redondeo bancario a 2 decimales.</li>
 *   <li>Aplicación de cupones válidos (VERANO2026) y manejo no bloqueante de cupones inválidos.</li>
 *   <li>Suspensión de venta en espera con expiración y cálculo de subtotal estimado.</li>
 * </ul>
 */
@DisplayName("CarritoService — F4 Tests Unitarios")
class CarritoServiceTest {

    private CarritoService servicio;

    @BeforeEach
    void setUp() {
        servicio = new CarritoService();
    }

    private CalculoCarritoRequest.ItemCarritoCalculoDto item(String sku, int cantidad, String precio) {
        return new CalculoCarritoRequest.ItemCarritoCalculoDto(sku, cantidad, new BigDecimal(precio));
    }

    @Nested
    @DisplayName("Cálculo Financiero y Desglose Tributario (RF-12)")
    class CalculoFinancieroTests {

        @Test
        @DisplayName("Calcula correctamente subtotal, base imponible e IGV 18% sin cupón")
        void calculaTotalesCorrectamenteSinDescuento() {
            var request = new CalculoCarritoRequest(
                    List.of(item("POL-RUN-M", 2, "50.00")),
                    null
            );

            CalculoCarritoResponse respuesta = servicio.calcularTotales(request);

            assertThat(respuesta.subtotalBruto()).isEqualByComparingTo("100.00");
            assertThat(respuesta.descuentoTotal()).isEqualByComparingTo("0.00");
            assertThat(respuesta.totalPagar()).isEqualByComparingTo("100.00");
            assertThat(respuesta.baseImponible()).isEqualByComparingTo("84.75");
            assertThat(respuesta.montoIgv()).isEqualByComparingTo("15.25");
            assertThat(respuesta.baseImponible().add(respuesta.montoIgv())).isEqualByComparingTo(respuesta.totalPagar());
            assertThat(respuesta.moneda()).isEqualTo("PEN");
            assertThat(respuesta.advertencias()).isEmpty();
        }

        @Test
        @DisplayName("Aplica cupón VERANO2026 con 10% de descuento y recalcula base e IGV según contrato")
        void aplicaCuponVerano2026Con10Porciento() {
            var request = new CalculoCarritoRequest(
                    List.of(item("CAM-RUN-M-AZUL", 2, "129.90")),
                    "VERANO2026"
            );

            CalculoCarritoResponse respuesta = servicio.calcularTotales(request);

            // Subtotal: 259.80, Descuento 10%: 25.98, Total: 233.82
            // Base Imponible: round(233.82 / 1.18, 2) = 198.15
            // Monto IGV: 233.82 - 198.15 = 35.67
            assertThat(respuesta.subtotalBruto()).isEqualByComparingTo("259.80");
            assertThat(respuesta.descuentoTotal()).isEqualByComparingTo("25.98");
            assertThat(respuesta.totalPagar()).isEqualByComparingTo("233.82");
            assertThat(respuesta.baseImponible()).isEqualByComparingTo("198.15");
            assertThat(respuesta.montoIgv()).isEqualByComparingTo("35.67");
            assertThat(respuesta.baseImponible().add(respuesta.montoIgv())).isEqualByComparingTo(respuesta.totalPagar());
            assertThat(respuesta.advertencias()).isEmpty();

            var linea = respuesta.lineas().get(0);
            assertThat(linea.descuento()).isEqualByComparingTo("25.98");
            assertThat(linea.precioFinal()).isEqualByComparingTo("233.82");
            assertThat(linea.promocionAplicada()).contains("Cupón VERANO2026");
        }

        @Test
        @DisplayName("Cupón inválido no bloquea la compra y reporta CUPON_INVALIDO en advertencias (RF-11)")
        void cuponInvalidoNoBloqueaYDevuelveAdvertencia() {
            var request = new CalculoCarritoRequest(
                    List.of(item("POL-RUN-M", 1, "80.00")),
                    "CUPON_EXPIRADO"
            );

            CalculoCarritoResponse respuesta = servicio.calcularTotales(request);

            assertThat(respuesta.subtotalBruto()).isEqualByComparingTo("80.00");
            assertThat(respuesta.descuentoTotal()).isEqualByComparingTo("0.00");
            assertThat(respuesta.totalPagar()).isEqualByComparingTo("80.00");
            assertThat(respuesta.advertencias()).contains("CUPON_INVALIDO");
        }

        @Test
        @DisplayName("Rechaza cálculo si la lista de items está vacía")
        void carritoVacioLanzaExcepcion() {
            var request = new CalculoCarritoRequest(List.of(), null);

            assertThatThrownBy(() -> servicio.calcularTotales(request))
                    .isInstanceOf(ReglaNegocioException.class)
                    .hasMessageContaining("al menos un item");
        }
    }

    @Nested
    @DisplayName("Suspensión de Venta / Carritos en Espera (RF-10)")
    class CarritosEnEsperaTests {

        @Test
        @DisplayName("Suspende venta con alias descriptivo, genera ID y fecha de expiración a 2 horas")
        void suspendeVentaEnEsperaCorrectamente() {
            var request = new CarritoEsperaDto(
                    null,
                    "Probador 3 - Zapatillas Running",
                    UUID.randomUUID(),
                    List.of(new CarritoEsperaDto.ItemCarritoEsperaDto(
                            "ZAP-RUN-41-NEG", "PROD-220", "Zapatilla Running Pegasus 40",
                            "41 / Negro", 1, new BigDecimal("299.90")
                    )),
                    null, null, null, null
            );

            CarritoEsperaDto guardado = servicio.guardarCarritoEspera(request);

            assertThat(guardado.carritoEsperaId()).isNotNull();
            assertThat(guardado.aliasTicket()).isEqualTo("Probador 3 - Zapatillas Running");
            assertThat(guardado.subtotalEstimado()).isEqualByComparingTo("299.90");
            assertThat(guardado.estado()).isEqualTo("SUSPENDIDO");
            assertThat(guardado.fechaCreacion()).isNotNull();
            assertThat(guardado.fechaExpiracion()).isAfter(guardado.fechaCreacion());
        }

        @Test
        @DisplayName("Permite listar carritos suspendidos guardados")
        void listaCarritosSuspendidos() {
            var request = new CarritoEsperaDto(
                    null,
                    "Probador 1 - Casaca",
                    null,
                    List.of(new CarritoEsperaDto.ItemCarritoEsperaDto(
                            "CAS-COR-L", "PROD-101", "Casaca Cortaviento",
                            "L / Azul", 1, new BigDecimal("150.00")
                    )),
                    null, null, null, null
            );
            servicio.guardarCarritoEspera(request);

            List<CarritoEsperaDto> lista = servicio.listarCarritosEspera("SUSPENDIDO");
            assertThat(lista).isNotEmpty();
            assertThat(lista.stream().anyMatch(c -> c.aliasTicket().equals("Probador 1 - Casaca"))).isTrue();
        }
    }
}

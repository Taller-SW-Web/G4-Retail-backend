package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoResponse;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.PromocionesPort;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Criterios de aceptación de RF-11 (F4) con el motor de promociones detrás de {@link PromocionesPort}.
 */
@DisplayName("CarritoService — RF-11 con el motor de promociones de G6")
class CarritoPromocionesTest {

    private static CalculoCarritoRequest carrito(String cupon) {
        return new CalculoCarritoRequest(List.of(
                new CalculoCarritoRequest.ItemCarritoCalculoDto("ZAPVEL-40-AZU", 1, new BigDecimal("249.00")),
                new CalculoCarritoRequest.ItemCarritoCalculoDto("MEDIAS-M", 3, new BigDecimal("20.00"))), cupon);
    }

    @Test
    @DisplayName("RF-11: una promoción automática aplica el descuento sin requerir cupón")
    void promocionAutomatica() {
        PromocionesPort automatica = motor(new PromocionesPort.Evaluacion("PRM-1", "Promoción PRM-1",
                new BigDecimal("30.90"), false), null);

        CalculoCarritoResponse r = new CarritoService(automatica).calcularTotales(carrito(null));

        assertThat(r.descuentoTotal()).isEqualByComparingTo("30.90");
        assertThat(r.totalPagar()).isEqualByComparingTo("278.10");
        assertThat(r.advertencias()).isEmpty();
    }

    @Test
    @DisplayName("RF-11: el descuento se reparte entre las líneas y la suma cuadra con el total")
    void repartoPorLinea() {
        PromocionesPort motor = motor(new PromocionesPort.Evaluacion("PRM-1", "Cupón VERANO2026",
                new BigDecimal("30.91"), false), null);

        CalculoCarritoResponse r = new CarritoService(motor).calcularTotales(carrito("VERANO2026"));

        BigDecimal suma = r.lineas().stream().map(CalculoCarritoResponse.LineaCalculoDto::descuento)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(suma).isEqualByComparingTo("30.91");
        assertThat(r.lineas()).allSatisfy(l -> assertThat(l.promocionAplicada()).isEqualTo("Cupón VERANO2026"));
    }

    @Test
    @DisplayName("RF-11: el cupón viaja en mayúsculas al motor")
    void cuponEnMayusculas() {
        AtomicReference<String> recibido = new AtomicReference<>();
        PromocionesPort motor = motor(PromocionesPort.Evaluacion.sinBeneficio(false), recibido);

        new CarritoService(motor).calcularTotales(carrito("  verano2026 "));

        assertThat(recibido.get()).isEqualTo("VERANO2026");
    }

    @Test
    @DisplayName("RF-11: un cupón falso o vencido deja CUPON_INVALIDO y mantiene los precios normales")
    void cuponRechazado() {
        PromocionesPort motor = motor(PromocionesPort.Evaluacion.sinBeneficio(true), null);

        CalculoCarritoResponse r = new CarritoService(motor).calcularTotales(carrito("VENCIDO2025"));

        assertThat(r.advertencias()).containsExactly("CUPON_INVALIDO");
        assertThat(r.descuentoTotal()).isEqualByComparingTo("0");
        assertThat(r.totalPagar()).isEqualByComparingTo("309.00");
    }

    @Test
    @DisplayName("Si el motor de G6 no responde, la venta sigue sin descuento y con advertencia")
    void motorCaido() {
        PromocionesPort caido = new PromocionesPort() {
            @Override public Evaluacion evaluar(List<Linea> lineas, String cupon) {
                throw new ServicioExternoNoDisponibleException("PROMOCIONES_NO_DISPONIBLES", "caído");
            }
            @Override public String origen() { return "PRUEBA"; }
        };

        CalculoCarritoResponse r = new CarritoService(caido).calcularTotales(carrito("VERANO2026"));

        assertThat(r.advertencias()).containsExactly("PROMOCIONES_NO_DISPONIBLES");
        assertThat(r.totalPagar()).isEqualByComparingTo("309.00");
    }

    @Test
    @DisplayName("Nunca descuenta más que el subtotal aunque el motor calcule con otros precios")
    void topeSubtotal() {
        PromocionesPort exagerado = motor(new PromocionesPort.Evaluacion("PRM-9", "Promo", new BigDecimal("999.00"), false), null);

        CalculoCarritoResponse r = new CarritoService(exagerado).calcularTotales(carrito(null));

        assertThat(r.totalPagar()).isEqualByComparingTo("0.00");
    }

    private static PromocionesPort motor(PromocionesPort.Evaluacion resultado, AtomicReference<String> cuponRecibido) {
        return new PromocionesPort() {
            @Override public Evaluacion evaluar(List<Linea> lineas, String cupon) {
                if (cuponRecibido != null) {
                    cuponRecibido.set(cupon);
                }
                return resultado;
            }
            @Override public String origen() { return "PRUEBA"; }
        };
    }
}

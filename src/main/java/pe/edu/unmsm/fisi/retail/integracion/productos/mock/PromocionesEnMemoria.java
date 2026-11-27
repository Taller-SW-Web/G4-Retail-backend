package pe.edu.unmsm.fisi.retail.integracion.productos.mock;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import pe.edu.unmsm.fisi.retail.integracion.productos.PromocionesPort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Doble del motor de promociones de G6 (contrato v0.5, §6): los cupones de prueba {@code VERANO2026}
 * y {@code DESCUENTO10} dan 10% sobre toda la cesta. No hay promociones automáticas sin cupón.
 *
 * <p>Son los mismos cupones que publica el doble de campañas de F11, para que el clic en una campaña
 * funcione de punta a punta.</p>
 */
@Component
@Profile("mock")
public class PromocionesEnMemoria implements PromocionesPort {

    private static final Map<String, String> CUPONES = Map.of(
            "VERANO2026", "CMP-2026-011",
            "DESCUENTO10", "CMP-2026-012");
    private static final BigDecimal DIEZ_POR_CIENTO = new BigDecimal("0.10");

    @Override
    public Evaluacion evaluar(List<Linea> lineas, String cupon) {
        if (cupon == null || cupon.isBlank()) {
            return Evaluacion.sinBeneficio(false);
        }
        String promocion = CUPONES.get(cupon);
        if (promocion == null) {
            return Evaluacion.sinBeneficio(true);
        }
        BigDecimal subtotal = lineas.stream()
                .map(l -> l.precioUnitario().multiply(BigDecimal.valueOf(l.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal descuento = subtotal.multiply(DIEZ_POR_CIENTO).setScale(2, RoundingMode.HALF_UP);
        return new Evaluacion(promocion, "10% Cupón " + cupon, descuento, false);
    }

    @Override
    public String origen() {
        return "EN_MEMORIA";
    }
}

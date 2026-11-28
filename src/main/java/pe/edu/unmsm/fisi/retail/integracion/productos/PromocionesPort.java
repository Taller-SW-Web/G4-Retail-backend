package pe.edu.unmsm.fisi.retail.integracion.productos;

import java.math.BigDecimal;
import java.util.List;

/**
 * Motor de promociones y cupones de Productos y Ofertas (G6) para el carrito de mostrador (F4, RF-11).
 *
 * <p>G6: {@code POST /promociones/evaluar} con {@code channel_id=RETAIL}, las líneas de la cesta y el
 * cupón opcional (contrato v0.5, §4.2). Responde el mejor beneficio aplicable a toda la cesta; Retail
 * lo reparte entre las líneas.</p>
 */
public interface PromocionesPort {

    /**
     * Evalúa la cesta.
     *
     * @param lineas lineas del carrito con el precio que ve el vendedor
     * @param cupon  cupón ingresado en mayúsculas, o {@code null}
     */
    Evaluacion evaluar(List<Linea> lineas, String cupon);

    /** Fuente de datos, para los logs: {@code PRODUCTOS_G6} o {@code EN_MEMORIA}. */
    String origen();

    /** Línea de la cesta. */
    record Linea(String sku, int cantidad, BigDecimal precioUnitario) {
    }

    /**
     * Resultado de la evaluación.
     *
     * @param promocionId    promoción aplicada, o {@code null} si no aplica ninguna
     * @param nombre         texto para el ticket, por ejemplo {@code 10% Cupón VERANO2026}
     * @param descuentoTotal descuento sobre toda la cesta (cero si no aplica)
     * @param cuponRechazado {@code true} si llegó un cupón y no se aceptó (inválido o vencido)
     */
    record Evaluacion(String promocionId, String nombre, BigDecimal descuentoTotal, boolean cuponRechazado) {

        public static Evaluacion sinBeneficio(boolean cuponRechazado) {
            return new Evaluacion(null, null, BigDecimal.ZERO, cuponRechazado);
        }

        public boolean aplica() {
            return descuentoTotal != null && descuentoTotal.signum() > 0;
        }
    }
}

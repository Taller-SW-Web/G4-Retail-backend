package pe.edu.unmsm.fisi.retail.service;

import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.model.TipoCambio;

import java.math.BigDecimal;

/**
 * Decide el tipo de un cambio directo (F9, contrato 5.9).
 *
 * <ul>
 *   <li><strong>Mismo modelo</strong> (mismo producto, otra talla o color, misma cantidad): siempre
 *       {@code MISMO_VALOR}, sin comparar precios, aunque el precio vigente haya cambiado desde la
 *       compra.</li>
 *   <li>Si no, se compara el total nuevo con el credito: igual es {@code MISMO_VALOR}, mayor es
 *       {@code MAYOR_VALOR} y menor se rechaza con {@code CAMBIO_MENOR_VALOR}: en mostrador no se
 *       emiten vales ni se devuelve dinero.</li>
 * </ul>
 */
final class ClasificadorCambio {

    private ClasificadorCambio() {
    }

    static TipoCambio clasificar(BigDecimal credito, BigDecimal totalNuevo, boolean mismoModelo) {
        if (mismoModelo) {
            return TipoCambio.MISMO_VALOR;
        }
        int comparacion = totalNuevo.compareTo(credito);
        if (comparacion == 0) {
            return TipoCambio.MISMO_VALOR;
        }
        if (comparacion > 0) {
            return TipoCambio.MAYOR_VALOR;
        }
        throw new ReglaNegocioException("CAMBIO_MENOR_VALOR",
                "Las prendas nuevas suman S/ " + totalNuevo.toPlainString() + " y el credito es S/ "
                        + credito.toPlainString() + ". El cambio debe ser de igual o mayor valor: no se emiten vales"
                        + " ni se devuelve dinero");
    }
}

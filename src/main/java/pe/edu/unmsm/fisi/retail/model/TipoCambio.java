package pe.edu.unmsm.fisi.retail.model;

/**
 * Tipo de un cambio directo de prenda (F9, contrato 5.9).
 *
 * <p>No hay menor valor: se rechaza con {@code CAMBIO_MENOR_VALOR}, porque en mostrador no se emiten
 * vales ni se devuelve dinero.</p>
 */
public enum TipoCambio {
    /** Mismo modelo, o el total nuevo igual al credito: sin cobro ni boleta. */
    MISMO_VALOR,
    /** El total nuevo supera al credito: se cobra la diferencia y se emite boleta. */
    MAYOR_VALOR
}

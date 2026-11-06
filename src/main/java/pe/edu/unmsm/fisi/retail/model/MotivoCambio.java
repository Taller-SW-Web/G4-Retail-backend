package pe.edu.unmsm.fisi.retail.model;

/**
 * Motivo por el que el cliente pide cambiar una prenda (F9, RF-24).
 *
 * <p>Los valores coinciden con el CHECK de {@code RET_SOLICITUD_CAMBIO_MOSTRADOR.motivo_cambio} y con
 * el contrato de API (seccion 5.9).</p>
 */
public enum MotivoCambio {

    /** El cliente quiere otra talla. Cambio ordinario: la prenda debe volver como se vendio. */
    CAMBIO_TALLA,

    /** El cliente quiere otro modelo. Cambio ordinario, con las mismas exigencias que el de talla. */
    CAMBIO_MODELO,

    /** La prenda tiene un defecto de fabrica. Se atiende por garantia y flexibiliza la inspeccion. */
    FALLA_FABRICA;

    /** Un cambio ordinario es el que no se debe a un defecto: la prenda tiene que volver intacta. */
    public boolean esOrdinario() {
        return this != FALLA_FABRICA;
    }
}

package pe.edu.unmsm.fisi.retail.model;

/** Ciclo de vida de un vale de compra emitido en mostrador (F9, RF-25). */
public enum EstadoVale {

    /** Recien emitido: el cliente puede usarlo hasta su fecha de vencimiento. */
    EMITIDO,

    /** Ya se uso en una compra. No puede volver a canjearse. */
    CANJEADO,

    /** Invalidado por un supervisor. */
    ANULADO
}

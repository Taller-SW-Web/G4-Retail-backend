package pe.edu.unmsm.fisi.retail.model;

/**
 * Estado de un cobro registrado en un turno de caja. Tabla {@code RET_COBRO_TURNO}.
 *
 * <p>Refleja en que punto esta la confirmacion de la venta con Ventas (G5). El efectivo cuenta para
 * el arqueo en todos los estados salvo {@link #ANULADO}: el dinero ya esta en la gaveta aunque G5
 * todavia no haya respondido.</p>
 */
public enum EstadoCobroTurno {

    /** Cobrado en mostrador; G5 aun no confirma (por ejemplo, timeout). Se reintenta con la misma llave. */
    PENDIENTE_CONFIRMACION,

    /** G5 confirmo el pedido. */
    CONFIRMADO,

    /** G5 rechazo la venta de forma definitiva; un supervisor debe resolverla. */
    EN_REVISION_SUPERVISOR,

    /** El cobro se revirtio y el efectivo ya no cuenta en la gaveta. */
    ANULADO
}

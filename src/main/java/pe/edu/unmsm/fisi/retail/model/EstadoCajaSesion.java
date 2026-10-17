package pe.edu.unmsm.fisi.retail.model;

/**
 * Estado del turno de caja (columna {@code estado} de {@code RET_CAJA_SESION}).
 *
 * <p>El turno nace {@link #ABIERTA} y termina en uno de los dos estados de cierre: {@link #CERRADA}
 * cuando el arqueo ciego cuadra al centavo, y {@link #OBSERVADA} cuando hay faltante o sobrante. La
 * distincion importa porque el CHECK de la tabla solo admite estos tres valores y el trigger de
 * auditoria decide, con ellos, si el cierre se registra como EXITOSO u OBSERVADO.</p>
 */
public enum EstadoCajaSesion {

    /** Turno en curso: la terminal admite ventas y movimientos menores. */
    ABIERTA,

    /** Turno cerrado y cuadrado: el efectivo declarado coincide con el del sistema. */
    CERRADA,

    /** Turno cerrado con diferencia: queda a revision del supervisor. */
    OBSERVADA
}

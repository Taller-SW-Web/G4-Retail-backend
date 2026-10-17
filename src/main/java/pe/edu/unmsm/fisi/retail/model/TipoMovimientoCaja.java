package pe.edu.unmsm.fisi.retail.model;

/**
 * Tipo de movimiento menor de efectivo durante el turno (RF-21).
 *
 * <p>Solo existen dos casos y el CHECK de {@code RET_MOVIMIENTO_CAJA} no admite mas: ingresar
 * sencillo a la gaveta o pagar un gasto menor con el efectivo de caja. Los cobros de venta no son
 * movimientos: esos entran por el modulo de Ventas (F5).</p>
 */
public enum TipoMovimientoCaja {

    /** Entra efectivo a la gaveta, por ejemplo una reposicion de sencillo desde tesoreria. */
    INGRESO_MENOR,

    /** Sale efectivo de la gaveta para cubrir un gasto menor, previa autorizacion del supervisor. */
    SALIDA_GASTO
}

package pe.edu.unmsm.fisi.retail.model;

/**
 * Si Ventas (G5) ya acepto el pedido del cambio, con el que se mueve el stock (contrato 5.9).
 */
public enum EstadoInventarioCambio {
    /** Ventas no respondio: el job reintenta el pedido con la misma clave cada 5 minutos. */
    PENDIENTE,
    /** Ventas acepto el pedido. */
    CONFIRMADO
}

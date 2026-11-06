package pe.edu.unmsm.fisi.retail.model;

/**
 * Resultado de la inspeccion fisica en mostrador (F9, RF-24).
 *
 * <p>Coincide con el CHECK de {@code RET_SOLICITUD_CAMBIO_MOSTRADOR.estado_aprobacion}.</p>
 */
public enum EstadoAprobacionCambio {
    APROBADO_EN_TIENDA,
    RECHAZADO
}

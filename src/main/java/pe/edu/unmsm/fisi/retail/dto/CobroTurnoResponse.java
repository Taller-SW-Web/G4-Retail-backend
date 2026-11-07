package pe.edu.unmsm.fisi.retail.dto;

import pe.edu.unmsm.fisi.retail.model.CobroTurno;

import java.math.BigDecimal;

/**
 * Cobro registrado en el turno.
 *
 * @param reutilizado {@code true} si la llave de idempotencia ya existia y se devolvio el cobro
 *                    anterior sin crear otro (reintento de una venta)
 */
public record CobroTurnoResponse(
        String cobroId,
        String turnoId,
        String idempotencyKey,
        String pedidoId,
        String medioPago,
        BigDecimal montoTotal,
        BigDecimal montoRecibido,
        BigDecimal vuelto,
        String estado,
        boolean reutilizado) {

    public static CobroTurnoResponse desde(CobroTurno cobro, boolean reutilizado) {
        return new CobroTurnoResponse(
                cobro.getIdCobro() == null ? null : cobro.getIdCobro().toString(),
                cobro.getCajaSesionId().toString(),
                cobro.getIdempotencyKey(),
                cobro.getPedidoId(),
                cobro.getMedioPago(),
                cobro.getMontoTotal(),
                cobro.getMontoRecibido(),
                cobro.getVuelto(),
                cobro.getEstado().name(),
                reutilizado);
    }
}

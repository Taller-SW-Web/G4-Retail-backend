package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;

/**
 * Datos que {@code VentaService} entrega a {@code CajaService.registrarCobro} para dejar el cobro
 * asociado al turno. Es una llamada interna entre servicios, no un endpoint HTTP.
 *
 * @param idempotencyKey    misma llave que viaja a Ventas (G5); identifica el cobro de forma unica
 * @param medioPago         {@code EFECTIVO} o {@code TARJETA_POS}
 * @param montoTotal        total de la venta
 * @param montoRecibido     efectivo entregado por el cliente (en tarjeta puede ser {@code null})
 * @param pedidoId          pedido de G5 si ya se conoce; puede ser {@code null}
 * @param comprobanteSerie  serie de la boleta o factura emitida ({@code B001}); opcional
 * @param comprobanteNumero correlativo del comprobante; opcional
 */
public record RegistroCobroRequest(
        String idempotencyKey,
        String medioPago,
        BigDecimal montoTotal,
        BigDecimal montoRecibido,
        String pedidoId,
        String comprobanteSerie,
        String comprobanteNumero) {
}

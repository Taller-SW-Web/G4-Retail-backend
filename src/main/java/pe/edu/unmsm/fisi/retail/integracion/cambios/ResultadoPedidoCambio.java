package pe.edu.unmsm.fisi.retail.integracion.cambios;

/**
 * Lo que respondio Ventas (G5) al pedido de un cambio de prenda (F9, contrato 5.9).
 *
 * <p>Se distinguen tres casos porque cada uno se trata distinto: un pedido confirmado cierra el
 * cambio; si Ventas no responde, el cambio de mismo valor queda {@code PENDIENTE} y se reintenta; si
 * Ventas lo rechaza ({@code 422}), el cambio no procede y se responde {@code PEDIDO_NO_APTO}.</p>
 */
public sealed interface ResultadoPedidoCambio {

    /** Ventas creo el pedido. */
    record Confirmado(String pedidoId, String estado) implements ResultadoPedidoCambio {}

    /** Ventas no respondio a tiempo o fallo ({@code 5xx}): se puede reintentar con la misma clave. */
    record NoDisponible(String motivo) implements ResultadoPedidoCambio {}

    /** Ventas rechazo el pedido, por ejemplo {@code PEDIDO_ORIGEN_NO_ENTREGADO}: no tiene sentido reintentar. */
    record Rechazado(String codigo, String detalle) implements ResultadoPedidoCambio {}
}

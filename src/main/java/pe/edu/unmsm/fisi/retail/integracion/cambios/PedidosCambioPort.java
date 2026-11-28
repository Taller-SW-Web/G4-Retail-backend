package pe.edu.unmsm.fisi.retail.integracion.cambios;

import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;

/**
 * Envia a Ventas (G5) el pedido de un cambio de prenda (F9, contrato 5.9).
 *
 * <p>Reemplaza a {@code InventarioPort}: Retail no llama a Inventory. La prenda nueva se reserva y
 * consume por este pedido, y Postventa reintegra la devuelta al recibir {@code CREDITO_CAMBIO},
 * {@code pedidoOrigenId} e {@code itemsDevueltos}.</p>
 */
public interface PedidosCambioPort {

    /**
     * @param pedido pedido con el credito del cambio en {@code pago}
     * @param clave  la misma {@code Idempotency-Key} del cambio: reenviarlo no crea otro pedido
     */
    ResultadoPedidoCambio enviar(VentasCrearPedidoRequest pedido, String clave);
}

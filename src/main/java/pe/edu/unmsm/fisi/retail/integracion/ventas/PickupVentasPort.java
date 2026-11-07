package pe.edu.unmsm.fisi.retail.integracion.ventas;

import pe.edu.unmsm.fisi.retail.dto.EntregaPickupRequest;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse;

import java.util.List;

/**
 * Puerto hacia Ventas y Postventa (G5) para el retiro en tienda (F7).
 *
 * <p>Ventas es el dueño del pedido y de su estado. Retail solo lee la bandeja de su tienda y avisa
 * cuando entregó el paquete; Ventas hace la transición a {@code ENTREGADO}.</p>
 *
 * <p>Hay dos implementaciones: {@link PickupVentasHttp} contra el servicio real de G5 y
 * {@link PickupVentasEnMemoria} con pedidos de ejemplo para pruebas y desarrollo sin credenciales.
 * La elección la hace {@link PickupVentasConfig}.</p>
 */
public interface PickupVentasPort {

    /** G5: {@code GET /api/v1/pedidos/pickup?tiendaId=}. Pedidos de la tienda pendientes de retiro. */
    List<PedidoPickupResponse> listarPendientes(String tiendaId);

    /** G5: {@code POST /api/v1/pedidos/{pedidoId}/entrega-pickup}. Marca el pedido como entregado. */
    EntregaPickupResponse confirmarEntrega(String pedidoId, EntregaPickupRequest entrega);

    /** Nombre corto de la fuente de datos, para los logs: {@code VENTAS_G5} o {@code EN_MEMORIA}. */
    String origen();
}

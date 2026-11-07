package pe.edu.unmsm.fisi.retail.integracion.ventas;

import java.util.List;
import java.util.Optional;

/**
 * Puerto hacia Ventas y Postventa (G5) para la consulta histórica de pedidos (F6).
 *
 * <p>Ventas es el dueño del pedido y de su historial de estados; Retail solo los lee. Hay dos
 * implementaciones: {@link PedidosVentasHttp} contra G5 real y {@link PedidosVentasEnMemoria} con
 * pedidos de ejemplo. La elección la hace {@link PedidosVentasConfig}.</p>
 */
public interface PedidosVentasPort {

    /** G5: {@code GET /api/v1/pedidos/{pedidoId}}. Vacío si Ventas no conoce el pedido. */
    Optional<PedidoVentas> consultar(String pedidoId);

    /** Pedidos del comprador con ese DNI o RUC, en cualquier orden. */
    List<PedidoVentas> buscarPorDocumento(String documento);

    /** Nombre corto de la fuente de datos, para los logs: {@code VENTAS_G5} o {@code EN_MEMORIA}. */
    String origen();
}

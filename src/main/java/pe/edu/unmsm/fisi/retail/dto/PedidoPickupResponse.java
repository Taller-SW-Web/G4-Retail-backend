package pe.edu.unmsm.fisi.retail.dto;

import java.util.List;

/**
 * Pedido pickup listo (o en camino) para retiro en la tienda del vendedor (RF-18).
 *
 * <p>Es cada elemento de {@code GET /api/v1/retail/pedidos/pickup}. El documento de reparto de la
 * Semana 8 lo lista como {@code PedidoPickupResponse}.</p>
 *
 * @param pedidoId          identificador opaco del pedido en Ventas, por ejemplo {@code PED-2026-08910}
 * @param clienteNombre     titular de la compra
 * @param clienteDni        DNI del titular (8 dígitos)
 * @param anaquelUbicacion  ubicación física del bulto en trastienda
 * @param fechaArriboTienda instante en que el bulto llegó a la tienda, en ISO-8601
 * @param estado            {@code LISTO_PARA_RECOJO}, {@code EN_PREPARACION} o {@code EN_CAMINO_A_TIENDA}
 * @param articulos         prendas que contiene el bulto
 */
public record PedidoPickupResponse(
        String pedidoId,
        String clienteNombre,
        String clienteDni,
        String anaquelUbicacion,
        String fechaArriboTienda,
        String estado,
        List<Articulo> articulos) {

    /** Prenda contenida en el bulto. */
    public record Articulo(String sku, String nombre, String talla, String color, int cantidad) {
    }
}

package pe.edu.unmsm.fisi.retail.integracion.boletas;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Boleta o factura emitida en mostrador, tal como la necesita F9 para validar un cambio.
 *
 * @param numero       serie y correlativo, por ejemplo {@code B001-00000045}
 * @param pedidoId     pedido central en Ventas (G5), si se conoce
 * @param fechaEmision momento de la emision
 * @param estado       estado del pedido: solo {@code PAGADO} y {@code ENTREGADO} admiten cambio
 * @param items        prendas compradas
 */
public record BoletaEmitida(String numero, String pedidoId, Instant fechaEmision, String estado, List<Item> items,
                           UUID ventaId) {

    public BoletaEmitida(String numero, String pedidoId, Instant fechaEmision, String estado, List<Item> items) {
        this(numero, pedidoId, fechaEmision, estado, items, null);
    }

    public BoletaEmitida {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * @param sku          SKU de la variante vendida
     * @param descripcion  descripcion legible para el vendedor
     * @param precioPagado precio unitario efectivamente cobrado, con descuentos aplicados
     * @param cantidad     unidades compradas
     */
    public record Item(String sku, String descripcion, BigDecimal precioPagado, int cantidad) {
    }
}

package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;

/**
 * Un pedido en la lista de {@code GET /api/v1/retail/pedidos} (F6, RF-16, contrato §5.6).
 *
 * @param fechaCreacion ISO-8601 con la hora de Lima ({@code -05:00})
 */
public record PedidoResumenDto(
        String pedidoId,
        String canal,
        String fechaCreacion,
        String estadoComercial,
        BigDecimal total) {
}

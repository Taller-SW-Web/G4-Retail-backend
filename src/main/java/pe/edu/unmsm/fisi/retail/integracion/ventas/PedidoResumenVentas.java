package pe.edu.unmsm.fisi.retail.integracion.ventas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Un resultado de la búsqueda de pedidos de Ventas (G5), {@code GET /api/v1/pedidos?buscar=}
 * (RF-16-DOC, contrato v0.5.2, §4.3 y §5.6). Solo trae pedidos {@code RETAIL} y de recojo en tienda.
 *
 * <p>Las fechas y los estados se leen como texto, igual que en {@link PedidoVentas}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PedidoResumenVentas(
        String pedidoId,
        String canal,
        String estado,
        BigDecimal total,
        String moneda,
        String fechaCreacion,
        String nombreCliente) {
}

package pe.edu.unmsm.fisi.retail.client.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Respuesta devuelta por POST /api/v1/pedidos del Módulo de Ventas (G5).
 * Alineada con ventas.pedido.dto.CrearPedidoResponse de G5.
 */
public record VentasPedidoResponse(
        String pedidoId,
        String estado,
        BigDecimal total,
        String moneda,
        String canal,
        OffsetDateTime fechaCreacion
) {}

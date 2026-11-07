package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Detalle y trazabilidad de un pedido: {@code GET /api/v1/retail/pedidos/{pedidoId}} (F6, RF-17,
 * contrato §5.6).
 *
 * <p>Los estados son los oficiales de Ventas (G5). {@code historialEstados} va en orden cronológico
 * ascendente y todas las fechas van en ISO-8601 con la hora de Lima ({@code -05:00}).</p>
 */
public record DetallePedidoHistoricoResponse(
        String pedidoId,
        String canal,
        String tipo,
        String estadoComercial,
        String estadoPago,
        String modalidad,
        String tiendaId,
        Cliente cliente,
        BigDecimal total,
        String moneda,
        String metodoPago,
        String fechaCreacion,
        String ultimaActualizacion,
        List<Linea> lineas,
        List<EventoEstado> historialEstados) {

    public record Cliente(String nombre, String tipoDocumento, String numeroDocumento) {
    }

    /** {@code talla} y {@code color} van vacíos mientras Ventas no los publique en el pedido. */
    public record Linea(String sku, String nombre, String talla, String color, int cantidad,
                        BigDecimal precioUnitario, BigDecimal subtotal) {
    }

    public record EventoEstado(String estado, String fecha, String motivo) {
    }
}

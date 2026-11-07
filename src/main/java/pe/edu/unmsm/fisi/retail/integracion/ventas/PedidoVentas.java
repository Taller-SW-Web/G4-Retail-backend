package pe.edu.unmsm.fisi.retail.integracion.ventas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pedido tal como lo devuelve Ventas (G5) en {@code GET /api/v1/pedidos/{pedidoId}}
 * ({@code ventas.pedido.dto.PedidoDetalle}).
 *
 * <p>Las fechas y los estados se leen como texto: si G5 agrega un estado nuevo o cambia el formato
 * de la fecha, la consulta no se rompe. La normalización la hace {@code HistorialPedidosService}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PedidoVentas(
        String pedidoId,
        String canal,
        String estado,
        String tipo,
        Contacto contacto,
        Pago pago,
        Envio envio,
        List<Item> items,
        String fechaCreacion,
        String ultimaActualizacion,
        List<Historial> historialEstados) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contacto(String nombreCompleto, String tipoDocumento, String numeroDocumento) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pago(BigDecimal total, String moneda, String metodoPago, String estado) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envio(String modalidad, String tiendaId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String sku, String descripcion, Integer cantidad, BigDecimal precioUnitario) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Historial(String estado, String fecha, String motivo) {
    }
}

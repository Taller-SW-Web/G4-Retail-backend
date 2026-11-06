package pe.edu.unmsm.fisi.retail.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Payload para POST /api/v1/pedidos del Módulo de Ventas y Postventa (G5).
 * Alineado exactamente con ventas.pedido.dto.CrearPedidoRequest de G5.
 */
public record VentasCrearPedidoRequest(
        String canal,
        Contacto contacto,
        List<Item> items,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Cupon cupon,
        Envio envio,
        Pago pago
) {
    public record Contacto(
            UUID clienteId,
            String nombreCompleto,
            String tipoDocumento,
            String numeroDocumento,
            String telefono,
            String email
    ) {}

    public record Item(
            String productoId,
            String sku,
            String descripcion,
            Integer cantidad,
            BigDecimal precioUnitario
    ) {}

    public record Cupon(
            String codigo,
            BigDecimal descuento,
            Boolean aplicado
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Envio(
            String modalidad,
            BigDecimal costo,
            String destinatario,
            String departamento,
            String provincia,
            String distrito,
            String direccion,
            String referencia,
            String tiendaId
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Pago(
            String metodoPago,
            String moneda,
            BigDecimal subtotal,
            BigDecimal descuentoCupon,
            BigDecimal costoEnvio,
            BigDecimal total,
            String referenciaOperacion,
            String comprobanteReferencia,
            BigDecimal montoEfectivo,
            BigDecimal montoTarjeta,
            OffsetDateTime fechaVenta
    ) {
        public Pago(String metodoPago, String moneda, BigDecimal subtotal, BigDecimal descuentoCupon,
                    BigDecimal costoEnvio, BigDecimal total, String referenciaOperacion,
                    BigDecimal montoEfectivo, BigDecimal montoTarjeta, OffsetDateTime fechaVenta) {
            this(metodoPago, moneda, subtotal, descuentoCupon, costoEnvio, total, referenciaOperacion,
                    null, montoEfectivo, montoTarjeta, fechaVenta);
        }
    }
}

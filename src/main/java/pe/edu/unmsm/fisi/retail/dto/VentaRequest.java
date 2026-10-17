package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Cuerpo del {@code POST /api/v1/retail/ventas}.
 *
 * <p>F5 — RF-13 / RF-14 / RF-15: cierre transaccional de la venta presencial en mostrador,
 * emision de boleta B001 y registro del pago (efectivo o tarjeta POS).</p>
 *
 * <p><strong>Límite de responsabilidad:</strong> este DTO no valida documentos de identidad
 * (eso es F3 — ClienteController) ni abre/cierra turnos de caja (eso es F8 — CajaController).
 * Solo recibe los datos necesarios para calcular los montos fiscales y persisitir la venta.</p>
 */
public record VentaRequest(

        /** Canal de origen. Siempre {@code "RETAIL"} para ventas presenciales en mostrador. */
        @NotBlank(message = "El canal es obligatorio")
        String canal,

        /** Identificador de la tienda donde se realiza la venta. */
        @NotNull(message = "El tiendaId es obligatorio")
        UUID tiendaId,

        /** Identificador del vendedor autenticado que atiende la venta. */
        @NotNull(message = "El vendedorId es obligatorio")
        UUID vendedorId,

        /** Identificador del cliente asociado a la transaccion (puede ser cliente anonimo). */
        @NotNull(message = "El clienteId es obligatorio")
        UUID clienteId,

        /** Lineas de detalle: cada item es una variante de prenda con su cantidad y precio final. */
        @NotEmpty(message = "La venta debe tener al menos un item")
        @Valid
        List<ItemVentaDto> items,

        /** Datos del pago: medio (EFECTIVO o TARJETA_POS) y montos. */
        @NotNull(message = "El pago es obligatorio")
        @Valid
        PagoDto pago,

        /** Datos del comprobante a emitir (BOLETA o FACTURA). */
        @NotNull(message = "El comprobante es obligatorio")
        @Valid
        ComprobanteDto comprobante,

        /** Si es {@code true}, se genera adicionalmente un ticket de cambio para regalo. */
        boolean emitirTicketRegalo

) {

    // ── DTOs anidados ────────────────────────────────────────────────────────

    /**
     * Una linea del detalle de venta.
     *
     * <p><strong>Limite:</strong> el descuento de stock es responsabilidad de CatalogoService (F2);
     * VentaService solo registra la transaccion y calcula los importes fiscales.</p>
     */
    public record ItemVentaDto(

            /** SKU de la variante de prenda (codigo de barras interno). */
            @NotBlank(message = "El sku es obligatorio")
            String sku,

            /** Unidades vendidas. Debe ser mayor que cero. */
            @Positive(message = "La cantidad debe ser mayor que cero")
            int cantidad,

            /** Precio unitario final aplicado al momento de la venta (ya con descuentos). */
            @NotNull(message = "El precioFinal es obligatorio")
            @DecimalMin(value = "0.01", message = "El precioFinal debe ser mayor que cero")
            BigDecimal precioFinal

    ) {}

    /** Datos del medio de pago utilizado en la transaccion. */
    public record PagoDto(

            /**
             * Medio de pago: {@code EFECTIVO} o {@code TARJETA_POS}.
             * Solo se admiten estos dos valores (seccion RF-14).
             */
            @NotNull(message = "El medioPago es obligatorio")
            @Pattern(regexp = "EFECTIVO|TARJETA_POS", message = "medioPago debe ser EFECTIVO o TARJETA_POS")
            String medioPago,

            /** Monto total de la transaccion (debe coincidir con la suma de items × precioFinal). */
            @NotNull(message = "El monto es obligatorio")
            @DecimalMin(value = "0.01", message = "El monto debe ser mayor que cero")
            BigDecimal monto,

            /**
             * Referencia del voucher o numero de operacion del POS fisico.
             * Obligatorio cuando {@code medioPago = TARJETA_POS}.
             */
            String referenciaOperacion

    ) {}

    /** Datos del comprobante tributario a emitir. */
    public record ComprobanteDto(

            /**
             * Tipo de comprobante: {@code BOLETA} o {@code FACTURA}.
             * La logica de negocio valida que FACTURA requiera un cliente con RUC.
             */
            @NotNull(message = "El tipo de comprobante es obligatorio")
            @Pattern(regexp = "BOLETA|FACTURA", message = "tipo debe ser BOLETA o FACTURA")
            String tipo,

            /** Numero de documento del cliente (DNI, RUC o vacio para anonimo). */
            String numeroDocumento,

            /** Nombre o razon social del cliente para imprimir en el comprobante. */
            String razonSocial

    ) {}
}

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

/**
 * Cuerpo del {@code POST /api/v1/retail/ventas}.
 *
 * <p>F5 — RF-13 / RF-14 / RF-15: cierre transaccional de la venta presencial en mostrador,
 * emision de boleta B001 y registro del pago (efectivo o tarjeta POS).</p>
 *
 * <p><strong>Alineado con el contrato v0.4 §5.5.</strong> Los campos {@code tiendaId} y
 * {@code vendedorId} no viajan en el body: se toman del perfil del usuario autenticado
 * (§2.4). Los precios los calcula el backend (§5.4); {@code precioFinal} se mantiene
 * temporalmente para los mocks de la Semana 8 hasta que CatalogoService este listo.</p>
 */
public record VentaRequest(

        /** Codigo de la terminal POS donde se realiza la venta. Ej: {@code POS-01}. */
        @NotBlank(message = "El terminalPos es obligatorio")
        String terminalPos,

        /** Identificador del turno de caja abierto en la terminal. */
        @NotBlank(message = "El turnoId es obligatorio")
        String turnoId,

        /** Identificador del cliente asociado a la transaccion (texto opaco de Seguridad). */
        String clienteId,

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
        boolean emitirTicketRegalo,

        /** Codigo de cupon promocional (opcional, evaluado en §5.4). */
        String cupon,

        /** Referencia a la evaluacion previa de precios de {@code /carritos/calcular} (opcional). */
        String evaluacionId

) {

    // ── DTOs anidados ────────────────────────────────────────────────────────

    /**
     * Una linea del detalle de venta.
     *
     * <p><strong>Nota Semana 8:</strong> {@code precioFinal} se mantiene para que el mock
     * pueda calcular totales sin depender de CatalogoService. En produccion, el precio
     * proviene de la evaluacion de precios (§5.4) y no del cliente.</p>
     */
    public record ItemVentaDto(

            /** SKU de la variante de prenda (codigo de barras interno). */
            @NotBlank(message = "El sku es obligatorio")
            String sku,

            /** Unidades vendidas. Debe ser mayor que cero. */
            @Positive(message = "La cantidad debe ser mayor que cero")
            int cantidad,

            /** Precio unitario final aplicado al momento de la venta (mock Semana 8). */
            @NotNull(message = "El precioFinal es obligatorio")
            @DecimalMin(value = "0.01", message = "El precioFinal debe ser mayor que cero")
            BigDecimal precioFinal

    ) {}

    /**
     * Datos del medio de pago utilizado en la transaccion.
     *
     * <p>Alineado con el contrato v0.4 §5.5: para EFECTIVO se usa
     * {@code montoRecibidoEfectivo} (que puede ser mayor al total, generando vuelto);
     * para TARJETA_POS se usa {@code montoTarjeta} y {@code referenciaOperacion}.</p>
     */
    public record PagoDto(

            /**
             * Medio de pago: {@code EFECTIVO} o {@code TARJETA_POS}.
             */
            @NotNull(message = "El medioPago es obligatorio")
            @Pattern(regexp = "EFECTIVO|TARJETA_POS", message = "medioPago debe ser EFECTIVO o TARJETA_POS")
            String medioPago,

            /**
             * Monto en efectivo recibido del cliente. Solo aplica cuando {@code medioPago = EFECTIVO}.
             * Debe ser mayor o igual al total a pagar; el excedente es el vuelto.
             */
            BigDecimal montoRecibidoEfectivo,

            /** Monto cargado a la tarjeta via POS fisico. Solo aplica cuando {@code medioPago = TARJETA_POS}. */
            BigDecimal montoTarjeta,

            /**
             * Referencia del voucher o numero de operacion del POS fisico.
             * Obligatorio cuando {@code medioPago = TARJETA_POS}, minimo 4 caracteres.
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

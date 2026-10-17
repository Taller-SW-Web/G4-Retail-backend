package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Respuesta del {@code POST /api/v1/retail/ventas}.
 *
 * <p>F5 — RF-15: datos del comprobante emitido para que el frontend renderice el ticket termico
 * de 80 mm (boleta B001) y, si aplica, el ticket de cambio para regalo.</p>
 *
 * <p><strong>Alineado con el contrato v0.4 §5.5.</strong></p>
 */
public record VentaResponse(

        /** Identificador interno de la transaccion de venta en Retail. */
        String ventaId,

        /** Identificador del pedido generado en el modulo de Ventas, por ejemplo {@code VNT-20261017-00001}. */
        String pedidoId,

        /** Estado del pedido. Siempre {@code PAGADO} para ventas presenciales en mostrador. */
        String estadoPedido,

        /** Resumen del pago procesado (incluye vuelto para efectivo). */
        PagoResumen pago,

        /** Datos fiscales del comprobante emitido. */
        ComprobanteEmitido comprobante,

        /**
         * Ticket de cambio para regalo. {@code null} si {@code emitirTicketRegalo = false}
         * en la solicitud.
         */
        TicketRegalo ticketRegalo

) {

    // ── DTOs anidados ────────────────────────────────────────────────────────

    /**
     * Resumen del pago procesado: medio, monto recibido y vuelto (contrato §5.5 — response.pago).
     */
    public record PagoResumen(

            /** Medio de pago utilizado: {@code EFECTIVO} o {@code TARJETA_POS}. */
            String medioPago,

            /** Monto recibido del cliente. Para efectivo, puede ser mayor al total. */
            BigDecimal montoRecibido,

            /** Vuelto entregado al cliente. {@code 0} para tarjeta. */
            BigDecimal vuelto

    ) {}

    /**
     * Comprobante emitido: boleta B001 o factura F001, con todos los datos fiscales
     * necesarios para el QR de verificacion SUNAT (seccion RF-15).
     */
    public record ComprobanteEmitido(

            /** Tipo de comprobante: {@code BOLETA} o {@code FACTURA}. */
            String tipo,

            /** Serie del comprobante, por ejemplo {@code B001} para boleta o {@code F001} para factura. */
            String serie,

            /** Correlativo numerico formateado con ceros a la izquierda, por ejemplo {@code 00001}. */
            String correlativo,

            /** Base imponible antes de IGV: {@code total / 1.18} redondeado a 2 decimales. */
            BigDecimal subtotal,

            /** IGV 18 %: {@code total - subtotal} redondeado a 2 decimales. */
            BigDecimal igv,

            /** Monto total cancelado (precio de lista con IGV incluido). */
            BigDecimal total,

            /** Fecha y hora de emision del comprobante. */
            OffsetDateTime fechaEmision,

            /** Cadena de verificacion SUNAT para el codigo QR del ticket. */
            String qr

    ) {}

    /**
     * Ticket de regalo / cambio: se emite cuando el cliente solicita envolver el articulo
     * como regalo y no desea que el receptor vea los precios.
     */
    public record TicketRegalo(

            /** Codigo alfanumerico de canje que se imprime en el QR del ticket de cambio. */
            String codigoCanje,

            /** Fecha limite hasta la cual el cliente puede realizar el cambio en cualquier tienda. */
            OffsetDateTime fechaLimiteCambio

    ) {}
}

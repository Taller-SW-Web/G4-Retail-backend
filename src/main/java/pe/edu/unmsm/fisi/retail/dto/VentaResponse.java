package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Respuesta del {@code POST /api/v1/retail/ventas}.
 *
 * <p>F5 — RF-15: datos del comprobante emitido para que el frontend renderice el ticket termico
 * de 80 mm (boleta B001) y, si aplica, el ticket de cambio para regalo.</p>
 */
public record VentaResponse(

        /** Identificador unico de la transaccion de venta, por ejemplo {@code VNT-20261017-00001}. */
        String pedidoId,

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
     * Comprobante emitido: boleta B001 o factura F001, con todos los datos fiscales
     * necesarios para el QR de verificacion SUNAT (seccion RF-15).
     */
    public record ComprobanteEmitido(

            /** Serie del comprobante, por ejemplo {@code B001} para boleta o {@code F001} para factura. */
            String serie,

            /** Correlativo numerico formateado con ceros a la izquierda, por ejemplo {@code 00001}. */
            String correlativo,

            /** Fecha y hora de emision del comprobante. */
            OffsetDateTime fechaEmision,

            /** Base imponible antes de IGV: {@code total / 1.18} redondeado a 2 decimales. */
            BigDecimal subtotal,

            /** IGV 18 %: {@code total - subtotal} redondeado a 2 decimales. */
            BigDecimal igv,

            /** Monto total cancelado (precio de lista con IGV incluido). */
            BigDecimal total

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

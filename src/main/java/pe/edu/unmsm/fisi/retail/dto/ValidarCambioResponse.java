package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta de {@code GET /api/v1/retail/cambios/validar/{boletaNumero}} (F9, RF-23).
 *
 * <p>Un comprobante fuera de plazo <strong>no es un error</strong> en esta consulta: se responde con
 * {@code plazoValido = false} para que la pantalla muestre la compra con la etiqueta roja de plazo
 * vencido, como pide la spec. El rechazo llega al intentar registrar el cambio.</p>
 *
 * @param boletaNumero      comprobante consultado
 * @param pedidoId          pedido central en Ventas (G5), si se conoce
 * @param fechaEmision      fecha de emision en ISO 8601 UTC
 * @param diasTranscurridos dias naturales desde la compra, contando la compra como el dia 0
 * @param plazoDias         plazo vigente
 * @param plazoValido       si todavia se puede cambiar
 * @param items             prendas compradas y cuantas unidades quedan disponibles para cambio
 */
public record ValidarCambioResponse(
        String boletaNumero,
        String pedidoId,
        String fechaEmision,
        long diasTranscurridos,
        int plazoDias,
        boolean plazoValido,
        List<ItemCambiable> items) {

    /**
     * @param cantidadDisponibleCambio unidades compradas menos las que ya se cambiaron con esta boleta
     */
    public record ItemCambiable(
            String sku,
            String descripcion,
            BigDecimal precioPagado,
            int cantidadComprada,
            int cantidadDisponibleCambio) {
    }
}

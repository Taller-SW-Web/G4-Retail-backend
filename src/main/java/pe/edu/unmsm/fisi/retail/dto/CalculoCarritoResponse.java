package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta con el balance financiero del carrito segun normativa SUNAT (F4).
 *
 * <p>Expone el desglose estricto: Subtotal Bruto, Descuentos, Total a Pagar,
 * Base Imponible (total / 1.18) e IGV 18% (total - baseImponible) con redondeo a 2 decimales (RF-12).</p>
 */
public record CalculoCarritoResponse(

        List<LineaCalculoDto> lineas,
        BigDecimal subtotalBruto,
        BigDecimal descuentoTotal,
        BigDecimal totalPagar,
        BigDecimal baseImponible,
        BigDecimal montoIgv,
        String moneda,
        String evaluacionId,
        List<String> advertencias

) {

    /**
     * Detalle financiero de cada linea del carrito.
     */
    public record LineaCalculoDto(

            String sku,
            Integer cantidad,
            BigDecimal precioUnitario,
            BigDecimal descuento,
            BigDecimal precioFinal,
            String promocionAplicada

    ) {
    }
}

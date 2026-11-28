package pe.edu.unmsm.fisi.retail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta {@code 201} de {@code POST /api/v1/retail/cambios} (F9, contrato 5.9).
 *
 * <p>En mismo valor, {@code diferencia} es {@code 0} y {@code pago} y {@code comprobante} van nulos;
 * {@code pedidoId} es nulo mientras {@code estadoInventario} sea {@code PENDIENTE}. El POS imprime la
 * constancia con {@code numeroConstancia}, {@code boletaOrigen}, las prendas y {@code fechaCambio}.</p>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CambioDirectoResponse(
        String cambioId,
        String numeroConstancia,
        String tipoCambio,
        String boletaOrigen,
        BigDecimal montoCredito,
        BigDecimal totalNuevo,
        BigDecimal diferencia,
        VentaResponse.PagoResumen pago,
        VentaResponse.ComprobanteEmitido comprobante,
        String pedidoId,
        String estadoInventario,
        String fechaCambio,
        Prenda devuelto,
        List<Prenda> nuevos) {

    /** Prenda de la constancia: SKU, unidades y precio por unidad. */
    public record Prenda(String sku, int cantidad, BigDecimal precioUnitario) {}
}

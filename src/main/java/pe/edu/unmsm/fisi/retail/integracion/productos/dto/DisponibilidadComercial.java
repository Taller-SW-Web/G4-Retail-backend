package pe.edu.unmsm.fisi.retail.integracion.productos.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Disponibilidad comercial de un SKU, segun {@code DisponibilidadComercial} del OpenAPI de G6.
 *
 * <p>Productos no expone cantidades a los canales (X-P0-03): entrega un estado, no un numero de
 * unidades.</p>
 *
 * @param sku    SKU consultado
 * @param status estado comercial informado por G6
 */
public record DisponibilidadComercial(
        @JsonProperty("sku") String sku,
        @JsonProperty("status") Estado status) {

    /**
     * Estados que publica G6.
     *
     * <p>Ojo: <strong>no son los mismos</strong> que usa el contrato de Retail, que habla de
     * {@code DISPONIBLE}, {@code ULTIMAS_UNIDADES}, {@code SOLO_ALMACEN_CENTRAL} y
     * {@code AGOTADO_TOTAL}. La traduccion entre ambos vocabularios es responsabilidad del servicio
     * de catalogo de Retail, y esta documentada en {@code docs/integracion-productos-g6.md}.</p>
     */
    public enum Estado {
        DISPONIBLE,
        STOCK_BAJO,
        AGOTADO,
        /** No hay abastecimiento unico verificable. Nunca equivale a agotado. */
        INDETERMINADO
    }
}

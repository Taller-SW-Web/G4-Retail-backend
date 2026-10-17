package pe.edu.unmsm.fisi.retail.integracion.productos.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Precio vigente de un SKU, segun {@code Precio} del OpenAPI de G6.
 *
 * <p>Los importes se modelan con {@link BigDecimal} y nunca con {@code double}: en dinero, la
 * aritmetica binaria de punto flotante introduce errores de redondeo que luego descuadran el
 * arqueo de caja.</p>
 *
 * @param sku           SKU al que corresponde el precio
 * @param precioRegular precio de lista
 * @param precioOferta  precio promocional, nulo cuando no hay oferta vigente
 * @param currency      moneda, {@code PEN} por defecto
 */
public record Precio(
        @JsonProperty("sku") String sku,
        @JsonProperty("precio_regular") BigDecimal precioRegular,
        @JsonProperty("precio_oferta") BigDecimal precioOferta,
        @JsonProperty("currency") String currency) {

    /** Precio que se le cobra al cliente: la oferta si existe, si no el regular. */
    public BigDecimal precioEfectivo() {
        return precioOferta != null ? precioOferta : precioRegular;
    }
}

package pe.edu.unmsm.fisi.retail.integracion.productos.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Pagina de productos, segun {@code PaginaProductosComercial} del OpenAPI de G6.
 *
 * <p>La paginacion de G6 empieza en <strong>1</strong>; la de Retail, en 0 (seccion 2.7 del
 * contrato v0.4). La conversion vive en el adaptador, nunca en el controlador.</p>
 *
 * @param items   productos de esta pagina
 * @param pagina  numero de pagina, empezando en 1
 * @param tamanio cantidad de elementos por pagina
 * @param total   total de productos que cumplen el filtro
 */
public record PaginaProductos(
        @JsonProperty("items") List<ProductoComercial> items,
        @JsonProperty("pagina") int pagina,
        @JsonProperty("tamanio") int tamanio,
        @JsonProperty("total") long total) {

    public PaginaProductos {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static PaginaProductos vacia(int pagina, int tamanio) {
        return new PaginaProductos(List.of(), pagina, tamanio, 0);
    }
}

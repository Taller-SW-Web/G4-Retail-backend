package pe.edu.unmsm.fisi.retail.dto;

import java.util.List;

/**
 * Pagina de resultados del catalogo, con la forma que fija la seccion 5.2 del contrato v0.4.
 *
 * <p>Cuidado con la paginacion: aqui {@code page} empieza en <strong>0</strong>, mientras que G6
 * numera desde 1. La conversion se hace en el adaptador ({@code FiltroCatalogo}), de modo que este
 * DTO siempre habla el idioma de Retail.</p>
 *
 * @param items productos de esta pagina
 * @param page  numero de pagina, empezando en 0
 * @param size  cantidad de elementos por pagina
 * @param total total de productos que cumplen el filtro
 */
public record CatalogoResponse(List<ProductoCatalogoResponse> items, int page, int size, long total) {

    public CatalogoResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static CatalogoResponse vacia(int page, int size) {
        return new CatalogoResponse(List.of(), page, size, 0);
    }
}

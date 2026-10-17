package pe.edu.unmsm.fisi.retail.integracion.productos.dto;

/**
 * Filtros de busqueda del catalogo, equivalentes a los parametros de {@code GET /productos} en G6.
 *
 * @param q           texto libre de busqueda, nulo si no se filtra
 * @param categoriaId categoria, nula si no se filtra
 * @param marcaId     marca, nula si no se filtra
 * @param pagina      numero de pagina <strong>empezando en 1</strong>, como exige G6
 * @param tamanio     elementos por pagina, entre 1 y 100
 */
public record FiltroCatalogo(String q, String categoriaId, String marcaId, int pagina, int tamanio) {

    private static final int TAMANIO_MAXIMO = 100;

    public FiltroCatalogo {
        if (pagina < 1) {
            throw new IllegalArgumentException("La paginacion de G6 empieza en 1, llego: " + pagina);
        }
        if (tamanio < 1 || tamanio > TAMANIO_MAXIMO) {
            throw new IllegalArgumentException("El tamanio debe estar entre 1 y " + TAMANIO_MAXIMO);
        }
    }

    /** Filtro sin criterios, util para listar el catalogo completo de la primera pagina. */
    public static FiltroCatalogo primeraPagina(int tamanio) {
        return new FiltroCatalogo(null, null, null, 1, tamanio);
    }

    /**
     * Traduce la paginacion de Retail, que empieza en 0 (seccion 2.7 del contrato), a la de G6,
     * que empieza en 1.
     */
    public static FiltroCatalogo desdePaginacionRetail(String q, String categoriaId, String marcaId,
                                                       int page, int size) {
        return new FiltroCatalogo(q, categoriaId, marcaId, page + 1, size);
    }
}

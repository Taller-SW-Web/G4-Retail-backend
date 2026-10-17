package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Articulo del catalogo con todo lo que el mostrador necesita ver de un golpe (RF-04).
 *
 * <p>Reune en una sola respuesta lo que G6 reparte entre tres operaciones: el producto, el precio
 * por SKU y la disponibilidad. Esa composicion es justamente el trabajo de esta fachada; la pantalla
 * de venta no puede permitirse tres viajes por articulo con un cliente esperando en la cola.</p>
 *
 * @param productoId  identificador del producto en G6
 * @param nombre      nombre comercial que lee el vendedor
 * @param marca       marca del articulo
 * @param categoria   categoria del articulo, por ejemplo {@code CAT-RUNNING}
 * @param disciplina  disciplina deportiva; ver la nota sobre el dato ausente en G6
 * @param precio      precio mas bajo entre las variantes, el "desde" que se muestra en la tarjeta
 * @param imagenUrl   primera imagen del producto, nula si el catalogo no trae ninguna
 * @param variantes   combinaciones de talla y color con su stock
 */
public record ProductoCatalogoResponse(
        String productoId,
        String nombre,
        String marca,
        String categoria,
        String disciplina,
        BigDecimal precio,
        String imagenUrl,
        List<VarianteResponse> variantes) {

    public ProductoCatalogoResponse {
        variantes = variantes == null ? List.of() : List.copyOf(variantes);
    }

    /**
     * Precio con el que se rotula la tarjeta: el mas bajo de las variantes.
     *
     * <p>El contrato pone un unico {@code precio} a nivel de producto, pero G6 cotiza por SKU. Se
     * elige el minimo porque es el unico que nunca le queda grande al cliente: rotular con un precio
     * mayor al de alguna talla seria prometer de menos, y promediar inventaria un precio que no
     * existe. El importe que se cobra sale siempre de la variante elegida.</p>
     */
    public static BigDecimal precioDesde(List<VarianteResponse> variantes) {
        return variantes.stream()
                .map(VarianteResponse::precio)
                .filter(precio -> precio != null)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }
}

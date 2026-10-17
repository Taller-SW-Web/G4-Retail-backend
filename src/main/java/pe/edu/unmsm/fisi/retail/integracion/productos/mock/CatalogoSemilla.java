package pe.edu.unmsm.fisi.retail.integracion.productos.mock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.Atributo;

import java.math.BigDecimal;
import java.util.List;

/**
 * Datos semilla del mock de Productos (G6), tal como viven en
 * {@code resources/mocks/catalogo-g6.json}.
 *
 * <p>Es la "base de datos" interna del mock. Reune en un solo archivo lo que el modulo real reparte
 * entre tres operaciones distintas (catalogo, precios y disponibilidad) y agrega el codigo de
 * barras de cada SKU. Son datos de G6, no de Retail, por eso viven aparte de las tablas propias
 * del modulo (database-per-service, seccion 6 del contrato v0.4).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogoSemilla(@JsonProperty("productos") List<Producto> productos) {

    public CatalogoSemilla {
        productos = productos == null ? List.of() : List.copyOf(productos);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Producto(
            @JsonProperty("product_id") String productId,
            @JsonProperty("slug") String slug,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("categoria_id") String categoriaId,
            @JsonProperty("tipo_producto_id") String tipoProductoId,
            @JsonProperty("marca_id") String marcaId,
            @JsonProperty("tiene_variantes") boolean tieneVariantes,
            @JsonProperty("sku_base") String skuBase,
            @JsonProperty("images") List<String> images,
            @JsonProperty("variantes") List<Variante> variantes) {

        public Producto {
            images = images == null ? List.of() : List.copyOf(images);
            variantes = variantes == null ? List.of() : List.copyOf(variantes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Variante(
            @JsonProperty("variant_id") String variantId,
            @JsonProperty("sku") String sku,
            @JsonProperty("identifying_attributes") List<Atributo> identifyingAttributes,
            @JsonProperty("non_identifying_attributes") List<Atributo> nonIdentifyingAttributes,
            @JsonProperty("images") List<String> images,
            @JsonProperty("codigo_barras") String codigoBarras,
            @JsonProperty("precio_regular") BigDecimal precioRegular,
            @JsonProperty("precio_oferta") BigDecimal precioOferta,
            @JsonProperty("disponibilidad") String disponibilidad) {

        public Variante {
            identifyingAttributes = identifyingAttributes == null ? List.of() : List.copyOf(identifyingAttributes);
            nonIdentifyingAttributes = nonIdentifyingAttributes == null ? List.of() : List.copyOf(nonIdentifyingAttributes);
            images = images == null ? List.of() : List.copyOf(images);
        }
    }
}

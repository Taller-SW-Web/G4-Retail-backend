package pe.edu.unmsm.fisi.retail.integracion.productos.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Producto del catalogo comercial, segun {@code ProductoResumenComercial} del OpenAPI de G6.
 *
 * <p>Dos cosas que conviene tener presentes al armar la respuesta de Retail: aqui
 * <strong>no viene el precio</strong>, que se consulta aparte por SKU, y la categoria y la marca
 * llegan como identificadores, no como nombres.</p>
 *
 * @param productId      identificador del producto en G6
 * @param slug           identificador legible en URL
 * @param name           nombre comercial
 * @param description    descripcion, puede ser nula
 * @param categoriaId    identificador de categoria
 * @param tipoProductoId identificador del tipo de producto
 * @param marcaId        identificador de marca
 * @param tieneVariantes si es true, las variantes vendibles se piden aparte
 * @param skuBase        SKU del producto sin variantes, nulo cuando si las tiene
 * @param images         imagenes del producto
 */
public record ProductoComercial(
        @JsonProperty("product_id") String productId,
        @JsonProperty("slug") String slug,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("categoria_id") String categoriaId,
        @JsonProperty("tipo_producto_id") String tipoProductoId,
        @JsonProperty("marca_id") String marcaId,
        @JsonProperty("tiene_variantes") boolean tieneVariantes,
        @JsonProperty("sku_base") String skuBase,
        @JsonProperty("images") List<String> images) {

    public ProductoComercial {
        images = images == null ? List.of() : List.copyOf(images);
    }
}

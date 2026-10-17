package pe.edu.unmsm.fisi.retail.integracion.productos.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * Variante vendible de un producto, segun {@code VarianteComercial} del OpenAPI de G6.
 *
 * <p>El SKU identifica la variante y es lo que resuelve el lector de codigo de barras.</p>
 *
 * @param variantId                identificador de la variante en G6
 * @param sku                      SKU vendible, por ejemplo {@code CAM-RUN-M-AZUL}
 * @param identifyingAttributes    atributos que distinguen la variante: talla y color
 * @param nonIdentifyingAttributes atributos descriptivos que no la distinguen
 * @param images                   imagenes propias de la variante
 */
public record VarianteComercial(
        @JsonProperty("variant_id") String variantId,
        @JsonProperty("sku") String sku,
        @JsonProperty("identifying_attributes") List<Atributo> identifyingAttributes,
        @JsonProperty("non_identifying_attributes") List<Atributo> nonIdentifyingAttributes,
        @JsonProperty("images") List<String> images) {

    public VarianteComercial {
        identifyingAttributes = identifyingAttributes == null ? List.of() : List.copyOf(identifyingAttributes);
        nonIdentifyingAttributes = nonIdentifyingAttributes == null ? List.of() : List.copyOf(nonIdentifyingAttributes);
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** Valor de un atributo identificador por su nombre, por ejemplo {@code Talla}. */
    public Optional<String> atributo(String nombre) {
        return identifyingAttributes.stream()
                .filter(a -> a.esNombre(nombre))
                .map(Atributo::valor)
                .findFirst();
    }
}

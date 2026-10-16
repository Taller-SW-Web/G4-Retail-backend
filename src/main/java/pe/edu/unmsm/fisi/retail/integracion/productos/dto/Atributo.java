package pe.edu.unmsm.fisi.retail.integracion.productos.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Atributo de un producto o variante, tal como lo publica G6 ({@code Atributo} en su OpenAPI).
 *
 * <p>De aqui salen la talla y el color de cada variante: vienen como pares nombre/valor, no como
 * campos fijos. El contrato de G6 permite que {@code valor} sea de cualquier tipo; Retail solo
 * consume los textuales, que son los que necesita mostrar en mostrador.</p>
 *
 * @param caracteristicaId identificador de la caracteristica en G6
 * @param nombre           nombre legible, por ejemplo {@code Talla} o {@code Color}
 * @param valor            valor textual, por ejemplo {@code M} o {@code Azul}
 */
public record Atributo(
        @JsonProperty("caracteristica_id") String caracteristicaId,
        @JsonProperty("nombre") String nombre,
        @JsonProperty("valor") String valor) {

    /** Compara el nombre sin distinguir mayusculas, que es como llegan desde G6. */
    public boolean esNombre(String otroNombre) {
        return nombre != null && nombre.equalsIgnoreCase(otroNombre);
    }
}

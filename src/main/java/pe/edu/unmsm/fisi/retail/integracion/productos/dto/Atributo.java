package pe.edu.unmsm.fisi.retail.integracion.productos.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Atributo de un producto o variante, tal como lo publica G6 ({@code Atributo} en su OpenAPI).
 *
 * <p>De aqui salen la talla y el color de cada variante: vienen como pares nombre/valor, no como
 * campos fijos. El contrato de G6 permite que {@code valor} sea de cualquier tipo (texto, numero,
 * booleano, lista u objeto); Retail lo guarda como texto para mostrarlo en mostrador: una lista se une
 * con comas y un objeto queda en su JSON.</p>
 *
 * @param caracteristicaId identificador de la caracteristica en G6
 * @param nombre           nombre legible, por ejemplo {@code Talla} o {@code Color}
 * @param valor            valor como texto, por ejemplo {@code M} o {@code Azul}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Atributo(
        @JsonProperty("caracteristica_id") String caracteristicaId,
        @JsonProperty("nombre") String nombre,
        @JsonProperty("valor") @JsonDeserialize(using = Atributo.ValorComoTexto.class) String valor) {

    /** Compara el nombre sin distinguir mayusculas, que es como llegan desde G6. */
    public boolean esNombre(String otroNombre) {
        return nombre != null && nombre.equalsIgnoreCase(otroNombre);
    }

    /** Lee un {@code valor} de cualquier tipo JSON como texto. */
    public static class ValorComoTexto extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext contexto) throws IOException {
            return comoTexto(parser.readValueAsTree());
        }

        static String comoTexto(JsonNode nodo) {
            if (nodo == null || nodo.isNull() || nodo.isMissingNode()) {
                return null;
            }
            if (nodo.isArray()) {
                List<String> partes = new ArrayList<>();
                nodo.forEach(elemento -> {
                    String texto = comoTexto(elemento);
                    if (texto != null) {
                        partes.add(texto);
                    }
                });
                return String.join(", ", partes);
            }
            return nodo.isValueNode() ? nodo.asText() : nodo.toString();
        }
    }
}

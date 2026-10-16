package pe.edu.unmsm.fisi.retail.dto;

import java.time.Instant;

/**
 * Respuesta del latido del servicio, con la forma que fija la seccion 5.1 del contrato v0.4.
 *
 * @param estado    {@code UP} cuando el servicio responde
 * @param timestamp momento de la respuesta, en ISO 8601 UTC
 */
public record SaludResponse(String estado, String timestamp) {

    public static SaludResponse arriba() {
        return new SaludResponse("UP", Instant.now().toString());
    }
}

package pe.edu.unmsm.fisi.retail.integracion.inventario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;
import pe.edu.unmsm.fisi.retail.model.OutboxEvento;
import pe.edu.unmsm.fisi.retail.service.outbox.DestinoOutbox;
import pe.edu.unmsm.fisi.retail.service.outbox.EnvioOutboxException;

/**
 * Adaptador HTTP real hacia Inventory de Productos (G6) para el reporte de incidencias (F12, B6).
 *
 * <p>Envía el payload del outbox ({@link IncidenciaReportadaPayload}, formato del OpenAPI de G6) a
 * {@code POST /api/v1/inventario/incidencias} con:</p>
 * <ul>
 *   <li>{@code Authorization: Bearer} con el token de servicio de {@code modulo-retail} para la audiencia
 *   {@code api-productos} (scope {@code inventario:incidencias:reportar});</li>
 *   <li>{@code Idempotency-Key} igual al {@code incidenciaId}, el mismo en cada reintento;</li>
 *   <li>{@code X-Correlation-Id} de la petición que originó la incidencia.</li>
 * </ul>
 *
 * <p>Clasifica la respuesta según el contrato v0.5, §2.9: {@code 408}, {@code 429}, {@code 5xx} y la
 * falta de respuesta son transitorios; el resto de {@code 4xx} es un rechazo definitivo.</p>
 */
@Slf4j
public class InventarioIncidenciasHttp implements DestinoOutbox {

    static final String RUTA = "/api/v1/inventario/incidencias";

    private final RestClient restClient;
    private final TokenServicioG7 tokenServicio;

    public InventarioIncidenciasHttp(RestClient restClient, TokenServicioG7 tokenServicio) {
        this.restClient = restClient;
        this.tokenServicio = tokenServicio;
    }

    @Override
    public String nombre() {
        return PRODUCTOS_G6;
    }

    @Override
    public String enviar(OutboxEvento evento) {
        String token;
        try {
            token = tokenServicio.obtener();
        } catch (RuntimeException e) {
            throw EnvioOutboxException.transitorio("Sin token de servicio de Seguridad: " + e.getMessage());
        }
        try {
            RestClient.RequestBodySpec peticion = restClient.post()
                    .uri(RUTA)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("Idempotency-Key", evento.getAgregadoId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);
            if (evento.getCorrelationId() != null) {
                peticion = peticion.header("X-Correlation-Id", evento.getCorrelationId());
            }
            RespuestaInventory respuesta = peticion.body(evento.getPayload())
                    .retrieve()
                    .body(RespuestaInventory.class);
            String referencia = respuesta == null ? null : respuesta.incidenciaId();
            log.info("Inventory (G6) aceptó la incidencia {} (incidenciaInventoryId={})",
                    evento.getAgregadoId(), referencia);
            return referencia;
        } catch (RestClientResponseException e) {
            throw EnvioOutboxException.porEstadoHttp(e.getStatusCode().value(),
                    "Inventory respondió " + e.getStatusText() + " " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw EnvioOutboxException.transitorio("Inventory no respondió: " + e.getMessage());
        }
    }

    @Override
    public String origen() {
        return "PRODUCTOS_G6";
    }

    /** {@code IncidenciaInventarioResponse} de G6; Retail solo guarda el id y el estado ({@code ABIERTA}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RespuestaInventory(@JsonProperty("incidencia_id") String incidenciaId, @JsonProperty("estado") String estado) {
    }
}

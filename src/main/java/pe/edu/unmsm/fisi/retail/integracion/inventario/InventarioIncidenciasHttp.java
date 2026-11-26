package pe.edu.unmsm.fisi.retail.integracion.inventario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.LinkedHashMap;
import java.util.Map;

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
 * <p>La URL base termina en {@code /api/v1} (contrato v0.5.2, §4.2). Antes de enviar, reemplaza el
 * {@code location_id} de la tienda ({@code store_code}, p. ej. {@code TIENDA-MIRAFLORES}) por el UUID de
 * ubicación de G6 configurado en {@code retail.integracion.inventario.ubicaciones} (§5.12).</p>
 *
 * <p>Clasifica la respuesta según el contrato v0.5, §2.9: {@code 408}, {@code 429}, {@code 5xx} y la
 * falta de respuesta son transitorios; el resto de {@code 4xx} es un rechazo definitivo.</p>
 */
@Slf4j
public class InventarioIncidenciasHttp implements DestinoOutbox {

    static final String RUTA = "/inventario/incidencias";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final TokenServicioG7 tokenServicio;
    private final Map<String, String> ubicacionesG6;

    public InventarioIncidenciasHttp(RestClient restClient, TokenServicioG7 tokenServicio) {
        this(restClient, tokenServicio, Map.of());
    }

    public InventarioIncidenciasHttp(RestClient restClient, TokenServicioG7 tokenServicio, Map<String, String> ubicacionesG6) {
        this.restClient = restClient;
        this.tokenServicio = tokenServicio;
        this.ubicacionesG6 = Map.copyOf(ubicacionesG6);
    }

    /** Lee pares {@code store_code=uuid} separados por coma; ignora los vacíos o mal formados. */
    public static Map<String, String> ubicaciones(String pares) {
        Map<String, String> resultado = new LinkedHashMap<>();
        if (pares == null) {
            return resultado;
        }
        for (String par : pares.split(",")) {
            String[] partes = par.split("=", 2);
            if (partes.length == 2 && !partes[0].isBlank() && !partes[1].isBlank()) {
                resultado.put(partes[0].trim(), partes[1].trim());
            }
        }
        return resultado;
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
            RespuestaInventory respuesta = peticion.body(conUbicacionG6(evento.getPayload()))
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

    /** Cambia el {@code location_id} por el de G6 si la tienda está configurada; si no, lo deja igual. */
    private String conUbicacionG6(String payload) {
        if (ubicacionesG6.isEmpty() || payload == null) {
            return payload;
        }
        try {
            if (JSON.readTree(payload) instanceof ObjectNode cuerpo && cuerpo.hasNonNull("location_id")) {
                String ubicacion = ubicacionesG6.get(cuerpo.get("location_id").asText());
                if (ubicacion != null) {
                    cuerpo.put("location_id", ubicacion);
                    return JSON.writeValueAsString(cuerpo);
                }
            }
            return payload;
        } catch (JsonProcessingException e) {
            return payload;
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

package pe.edu.unmsm.fisi.retail.integracion.seguridad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Token de servicio que Seguridad (G7) emite a Retail para llamar a otros módulos.
 *
 * <p>Usa el flujo {@code client_credentials} contra {@code POST /api/v1/auth/token}. El token se
 * guarda en memoria y se reutiliza hasta un minuto antes de vencer, para no pedir uno nuevo en cada
 * llamada.</p>
 *
 * <p>El secreto del cliente <strong>nunca</strong> se escribe en el repositorio: llega por la
 * variable de entorno {@code G7_CLIENT_SECRET}. Si no está configurado, {@link #configurado()}
 * devuelve {@code false} y quien lo use decide qué hacer.</p>
 */
@Slf4j
@Component
public class TokenServicioG7 {

    /** Margen antes del vencimiento en el que el token ya se considera viejo. */
    private static final Duration MARGEN_RENOVACION = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final String audience;
    private final Clock reloj;

    private String tokenActual;
    private Instant venceEn = Instant.EPOCH;

    @Autowired
    public TokenServicioG7(
            @Value("${retail.integracion.g7.token-url:https://g7-auth-service.onrender.com/api/v1/auth/token}") String tokenUrl,
            @Value("${retail.integracion.g7.client-id:modulo-retail}") String clientId,
            @Value("${retail.integracion.g7.client-secret:}") String clientSecret,
            @Value("${retail.integracion.g7.audience-ventas:api-ventas}") String audience,
            @Value("${retail.integracion.g7.timeout-ms:20000}") int timeoutMs) {
        this(RestClient.builder().baseUrl(tokenUrl).requestFactory(fabrica(timeoutMs)).build(),
                clientId, clientSecret, audience, Clock.systemUTC());
    }

    /** Constructor para pruebas: permite inyectar el cliente HTTP y el reloj. */
    public TokenServicioG7(RestClient restClient, String clientId, String clientSecret, String audience, Clock reloj) {
        this.restClient = restClient;
        this.clientId = clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.audience = audience;
        this.reloj = reloj;
    }

    /** {@code true} si hay secreto configurado y por lo tanto se puede pedir token a G7. */
    public boolean configurado() {
        return !clientSecret.isEmpty();
    }

    /**
     * Devuelve un token vigente, pidiendo uno nuevo a G7 solo si el anterior está por vencer.
     *
     * @throws ServicioExternoNoDisponibleException si G7 no responde o rechaza las credenciales
     */
    public synchronized String obtener() {
        Instant ahora = reloj.instant();
        if (tokenActual != null && ahora.isBefore(venceEn.minus(MARGEN_RENOVACION))) {
            return tokenActual;
        }

        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("grant_type", "client_credentials");
        formulario.add("client_id", clientId);
        formulario.add("client_secret", clientSecret);
        formulario.add("audience", audience);

        RespuestaToken respuesta;
        try {
            respuesta = restClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formulario)
                    .retrieve()
                    .body(RespuestaToken.class);
        } catch (RestClientException e) {
            log.warn("Seguridad (G7) no entregó el token de servicio: {}", e.getMessage());
            throw new ServicioExternoNoDisponibleException("SEGURIDAD_NO_DISPONIBLE",
                    "No se pudo obtener el token de servicio de Seguridad");
        }

        if (respuesta == null || respuesta.accessToken() == null || respuesta.accessToken().isBlank()) {
            throw new ServicioExternoNoDisponibleException("SEGURIDAD_NO_DISPONIBLE",
                    "Seguridad respondió sin token de servicio");
        }

        tokenActual = respuesta.accessToken();
        long segundos = respuesta.expiresIn() != null ? respuesta.expiresIn() : 300;
        venceEn = ahora.plusSeconds(segundos);
        log.info("Token de servicio G7 renovado (audience={}, vence en {} s)", audience, segundos);
        return tokenActual;
    }

    private static SimpleClientHttpRequestFactory fabrica(int timeoutMs) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofMillis(timeoutMs));
        fabrica.setReadTimeout(Duration.ofMillis(timeoutMs));
        return fabrica;
    }

    /** Respuesta de {@code POST /api/v1/auth/token} de G7. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RespuestaToken(String accessToken, String tokenType, Long expiresIn) {
    }
}

package pe.edu.unmsm.fisi.retail.client;

import pe.edu.unmsm.fisi.retail.config.gestionapi.CorrelacionSaliente;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;
import pe.edu.unmsm.fisi.retail.client.dto.VentasPedidoResponse;
import pe.edu.unmsm.fisi.retail.integracion.cambios.ResultadoPedidoCambio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.time.Duration;
import java.util.Optional;

/**
 * Cliente REST para comunicación intermodular con el Módulo de Ventas y Postventa (G5).
 *
 * <p>Implementa el acuerdo intermodular (§4.3 de api-contracts.md):</p>
 * <ul>
 *   <li>Consumo de {@code POST /api/v1/pedidos} con modalidad {@code RETAIL}.</li>
 *   <li>Cabecera {@code Idempotency-Key} obligatoria para tolerancia a fallos.</li>
 *   <li>Resiliencia ante caídas de red o timeouts (fallback controlado).</li>
 * </ul>
 */
@Slf4j
@Component
public class VentasClient {

    private final RestClient restClient;
    private final boolean habilitado;
    private final TokenServicioG7 tokenServicio;

    @Autowired
    public VentasClient(
            @Value("${retail.integracion.ventas.url:https://g5-servicio-ventas.onrender.com}") String baseUrl,
            @Value("${retail.integracion.ventas.habilitado:true}") boolean habilitado,
            @Value("${retail.integracion.ventas.timeout-ms:3500}") int timeoutMs,
            @Autowired(required = false) RestClient.Builder restClientBuilder,
            TokenServicioG7 tokenServicio
    ) {
        this.habilitado = habilitado;
        this.tokenServicio = tokenServicio;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        RestClient.Builder builder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(CorrelacionSaliente.INSTANCIA)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("VentasClient inicializado: url={} habilitado={} timeoutMs={}", baseUrl, habilitado, timeoutMs);
    }

    /** Constructor para tests que permite inyectar un RestClient personalizado. */
    public VentasClient(RestClient restClient, boolean habilitado) {
        this.restClient = restClient;
        this.habilitado = habilitado;
        this.tokenServicio = null;
    }

    /**
     * Envía la orden de compra presencial hacia el microservicio de Ventas.
     *
     * @param request        datos del pedido con formato acordado G4 ↔ G5
     * @param idempotencyKey identificador de idempotencia de la operación
     * @return respuesta de Ventas con el {@code pedidoId} oficial, o {@code Optional.empty()}
     *         si el servicio está inactivo o la llamada falla (activando fallback local)
     */
    public Optional<VentasPedidoResponse> crearPedido(VentasCrearPedidoRequest request, String idempotencyKey) {
        if (!habilitado) {
            log.debug("Integración con Ventas deshabilitada por configuración. Omitiendo llamada HTTP.");
            return Optional.empty();
        }

        try {
            log.info("Enviando pedido a Ventas: idempotencyKey={}", idempotencyKey);

            VentasPedidoResponse respuesta = restClient.post()
                    .uri("/api/v1/pedidos")
                    .header("Idempotency-Key", idempotencyKey != null ? idempotencyKey : "")
                    .headers(headers -> {
                        if (tokenServicio != null && tokenServicio.configurado()) {
                            headers.setBearerAuth(tokenServicio.obtener());
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(VentasPedidoResponse.class);

            if (respuesta != null && respuesta.pedidoId() != null) {
                log.info("Pedido registrado exitosamente en Ventas: pedidoId={} estado={}",
                        respuesta.pedidoId(), respuesta.estado());
                return Optional.of(respuesta);
            }

            log.warn("Ventas respondió 2xx pero sin cuerpo válido o pedidoId nulo");
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Fallo en la comunicación con Ventas (activando fallback local): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Envia el pedido de un cambio de prenda (F9, contrato 5.9).
     *
     * <p>A diferencia de {@link #crearPedido}, distingue un rechazo de Ventas ({@code 4xx}, por ejemplo
     * {@code PEDIDO_ORIGEN_NO_ENTREGADO}) de una caida: el rechazo no se reintenta y la caida si.</p>
     */
    public ResultadoPedidoCambio crearPedidoCambio(VentasCrearPedidoRequest request, String idempotencyKey) {
        if (!habilitado) {
            return new ResultadoPedidoCambio.NoDisponible("Integracion con Ventas deshabilitada");
        }
        try {
            VentasPedidoResponse respuesta = restClient.post()
                    .uri("/api/v1/pedidos")
                    .header("Idempotency-Key", idempotencyKey)
                    .headers(headers -> {
                        if (tokenServicio != null && tokenServicio.configurado()) {
                            headers.setBearerAuth(tokenServicio.obtener());
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(VentasPedidoResponse.class);
            if (respuesta == null || respuesta.pedidoId() == null || respuesta.pedidoId().isBlank()) {
                return new ResultadoPedidoCambio.NoDisponible("Ventas respondio sin pedidoId");
            }
            return new ResultadoPedidoCambio.Confirmado(respuesta.pedidoId(), respuesta.estado());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                return new ResultadoPedidoCambio.Rechazado(codigoDe(e.getResponseBodyAsString()), e.getStatusText());
            }
            log.warn("Ventas fallo al registrar el pedido del cambio: {}", e.getStatusCode());
            return new ResultadoPedidoCambio.NoDisponible("Ventas respondio " + e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Sin respuesta de Ventas para el pedido del cambio: {}", e.getMessage());
            return new ResultadoPedidoCambio.NoDisponible(e.getMessage());
        }
    }

    /** Lee el {@code code} de un problem+json; si no viene, el rechazo queda como {@code RECHAZADO_POR_VENTAS}. */
    private static String codigoDe(String cuerpo) {
        try {
            JsonNode nodo = new ObjectMapper().readTree(cuerpo);
            if (nodo != null && nodo.hasNonNull("code")) {
                return nodo.get("code").asText();
            }
        } catch (Exception ignorado) {
            // cuerpo vacio o que no es JSON
        }
        return "RECHAZADO_POR_VENTAS";
    }
}

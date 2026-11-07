package pe.edu.unmsm.fisi.retail.integracion.ventas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupRequest;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.util.List;

/**
 * Adaptador HTTP real hacia Ventas (G5) para el retiro en tienda.
 *
 * <p>Cada llamada lleva {@code Authorization: Bearer} con el token de servicio que emite Seguridad
 * (G7) para el cliente {@code modulo-retail}. G5 exige los scopes {@code ventas:pedidos:consultar}
 * (bandeja) y {@code ventas:pedidos:estado} (entrega).</p>
 *
 * <p>Los errores de G5 se traducen a las excepciones de Retail para que la SPA reciba siempre el
 * formato de error del contrato: {@code 404}, {@code 409} y {@code 422} pasan con su código; una
 * caída, un timeout o un {@code 5xx} se responden como {@code 503 VENTAS_NO_DISPONIBLE}.</p>
 */
@Slf4j
public class PickupVentasHttp implements PickupVentasPort {

    private static final ParameterizedTypeReference<List<PedidoPickupResponse>> LISTA_PEDIDOS =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final TokenServicioG7 tokenServicio;
    private final ObjectMapper mapper = new ObjectMapper();

    public PickupVentasHttp(RestClient restClient, TokenServicioG7 tokenServicio) {
        this.restClient = restClient;
        this.tokenServicio = tokenServicio;
    }

    @Override
    public List<PedidoPickupResponse> listarPendientes(String tiendaId) {
        try {
            List<PedidoPickupResponse> pedidos = restClient.get()
                    .uri(uri -> uri.path("/api/v1/pedidos/pickup").queryParam("tiendaId", tiendaId).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenServicio.obtener())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(LISTA_PEDIDOS);
            log.info("Bandeja pickup de Ventas (G5) para {}: {} pedidos", tiendaId, pedidos == null ? 0 : pedidos.size());
            return pedidos == null ? List.of() : pedidos;
        } catch (RestClientResponseException e) {
            throw traducir(e, "consultar la bandeja pickup");
        } catch (RestClientException e) {
            throw caida(e, "consultar la bandeja pickup");
        }
    }

    @Override
    public EntregaPickupResponse confirmarEntrega(String pedidoId, EntregaPickupRequest entrega) {
        EntregaVentas cuerpo = new EntregaVentas(entrega.dniRecoge(), entrega.nombreRecoge(),
                entrega.esTerceroAutorizado(), entrega.codigoRetiro());
        try {
            EntregaPickupResponse constancia = restClient.post()
                    .uri("/api/v1/pedidos/{pedidoId}/entrega-pickup", pedidoId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenServicio.obtener())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cuerpo)
                    .retrieve()
                    .body(EntregaPickupResponse.class);
            if (constancia == null) {
                throw new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE",
                        "Ventas confirmó la entrega sin devolver la constancia");
            }
            log.info("Entrega pickup confirmada en Ventas (G5): pedido={} constancia={}", pedidoId, constancia.constanciaId());
            return constancia;
        } catch (RestClientResponseException e) {
            throw traducir(e, "confirmar la entrega pickup");
        } catch (RestClientException e) {
            throw caida(e, "confirmar la entrega pickup");
        }
    }

    @Override
    public String origen() {
        return "VENTAS_G5";
    }

    /** Pasa a Retail el error de negocio de G5 conservando su código cuando lo trae. */
    private RuntimeException traducir(RestClientResponseException e, String operacion) {
        int estado = e.getStatusCode().value();
        ErrorVentas error = leerError(e.getResponseBodyAsString());
        String mensaje = error.mensaje() != null ? error.mensaje() : "Ventas rechazó la operación";
        log.warn("Ventas (G5) respondió {} al {}: {} {}", estado, operacion, error.codigo(), mensaje);

        return switch (estado) {
            case 404 -> new RecursoNoEncontradoException(codigoO(error, "PEDIDO_NO_ENCONTRADO"), mensaje);
            case 409 -> new ConflictoException(codigoO(error, "PEDIDO_NO_LISTO_PARA_RECOJO"), mensaje);
            case 400, 422 -> new ReglaNegocioException(codigoO(error, "VENTAS_RECHAZO_LA_SOLICITUD"), mensaje);
            case 401, 403 -> new ServicioExternoNoDisponibleException("VENTAS_CREDENCIAL_RECHAZADA",
                    "Ventas no aceptó la credencial de servicio de Retail");
            default -> new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE",
                    "Ventas no pudo atender la solicitud. Reintente en unos segundos");
        };
    }

    private RuntimeException caida(RestClientException e, String operacion) {
        log.warn("Ventas (G5) no respondió al {}: {}", operacion, e.getMessage());
        return new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE",
                "Ventas no respondió a tiempo. Reintente en unos segundos");
    }

    private ErrorVentas leerError(String cuerpo) {
        try {
            return cuerpo == null || cuerpo.isBlank()
                    ? new ErrorVentas(null, null)
                    : mapper.readValue(cuerpo, ErrorVentas.class);
        } catch (Exception ignorado) {
            return new ErrorVentas(null, null);
        }
    }

    private static String codigoO(ErrorVentas error, String porDefecto) {
        return error.codigo() != null && !error.codigo().isBlank() ? error.codigo() : porDefecto;
    }

    /** Cuerpo que espera G5 en {@code entrega-pickup} ({@code ventas.pedido.dto.EntregaPickupRequest}). */
    record EntregaVentas(String dniRecoge, String nombreRecoge, boolean esTerceroAutorizado, String codigoRetiro) {
    }

    /** Formato de error de G5: {@code {"codigo": "...", "mensaje": "..."}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorVentas(String codigo, String mensaje) {
    }
}

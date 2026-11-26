package pe.edu.unmsm.fisi.retail.integracion.seguridad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.DatoInvalidoException;
import pe.edu.unmsm.fisi.retail.exception.DemasiadasSolicitudesException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;

/**
 * Adaptador HTTP real hacia {@code /usuarios/*} de Seguridad (G7) para F3 (contrato v0.5, §4.1).
 *
 * <p>Las dos operaciones exigen el <strong>token del vendedor</strong> (G7 responde {@code 403} a un token
 * de servicio), así que se reenvía el {@code Authorization} de la petición en curso.</p>
 *
 * <ul>
 *   <li>{@code POST /api/v1/usuarios/busqueda-documento} {@code {tipoDocumento, numeroDocumento}} →
 *   {@code {id, nombreCompleto, documentoEnmascarado, estado}}. {@code 404} si no existe; {@code 429}
 *   con {@code Retry-After} al pasar las 60 búsquedas por hora.</li>
 *   <li>{@code POST /api/v1/usuarios/clientes} {@code {correo, nombres, apellidos, celular?, tipoDocumento?,
 *   numeroDocumento?}} → {@code 201 {id, estado}}; {@code 409} si el correo o el documento ya existen.</li>
 * </ul>
 */
@Slf4j
public class ClientesG7Http {

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClientesG7Http(RestClient restClient) {
        this.restClient = restClient;
    }

    /** Cliente encontrado por documento ({@code ClienteEncontrado} de G7). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClienteEncontrado(String id, String nombreCompleto, String documentoEnmascarado, String estado) {
    }

    /** Cuenta creada por el registro asistido. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CuentaCreada(String id, String estado) {
    }

    /** {@code RegistroAsistidoRequest} de G7. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegistroAsistido(String correo, String nombres, String apellidos, String celular,
                                   String tipoDocumento, String numeroDocumento) {
    }

    public ClienteEncontrado buscar(String tokenVendedor, String tipoDocumento, String numeroDocumento) {
        try {
            return restClient.post()
                    .uri("/api/v1/usuarios/busqueda-documento")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenVendedor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Busqueda(tipoDocumento, numeroDocumento))
                    .retrieve()
                    .body(ClienteEncontrado.class);
        } catch (RestClientResponseException e) {
            throw traducir(e, "buscar el cliente");
        } catch (RestClientException e) {
            throw caida(e);
        }
    }

    public CuentaCreada registrar(String tokenVendedor, RegistroAsistido registro) {
        try {
            return restClient.post()
                    .uri("/api/v1/usuarios/clientes")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenVendedor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(registro)
                    .retrieve()
                    .body(CuentaCreada.class);
        } catch (RestClientResponseException e) {
            throw traducir(e, "registrar el cliente");
        } catch (RestClientException e) {
            throw caida(e);
        }
    }

    private RuntimeException traducir(RestClientResponseException e, String operacion) {
        int estado = e.getStatusCode().value();
        String codigo = codigoDe(e.getResponseBodyAsString());
        log.warn("Seguridad (G7) respondió {} {} al {}", estado, codigo, operacion);
        return switch (estado) {
            case 404 -> new RecursoNoEncontradoException("CLIENTE_NO_ENCONTRADO",
                    "El documento no corresponde a un cliente registrado");
            case 409 -> "CORREO_NO_DISPONIBLE".equals(codigo)
                    ? new ConflictoException("CORREO_NO_DISPONIBLE", "El correo ya pertenece a otra cuenta")
                    : new ConflictoException("CLIENTE_YA_REGISTRADO", "El documento ya está registrado; búsquelo por documento");
            case 429 -> new DemasiadasSolicitudesException(
                    "Se alcanzó el límite de búsquedas de clientes de Seguridad. Reintente más tarde", retryAfter(e));
            case 400, 422 -> new DatoInvalidoException("documento", "Seguridad rechazó los datos del cliente");
            case 401, 403 -> new AccesoDenegadoException("SEGURIDAD_SIN_PERMISO_CLIENTES",
                    "Su usuario no tiene permiso en Seguridad para " + operacion);
            default -> new ServicioExternoNoDisponibleException("SEGURIDAD_NO_DISPONIBLE",
                    "Seguridad no pudo atender la solicitud. Reintente en unos segundos");
        };
    }

    private static RuntimeException caida(RestClientException e) {
        log.warn("Seguridad (G7) no respondió: {}", e.getMessage());
        return new ServicioExternoNoDisponibleException("SEGURIDAD_NO_DISPONIBLE",
                "Seguridad no respondió a tiempo. Reintente en unos segundos");
    }

    private static long retryAfter(RestClientResponseException e) {
        String valor = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return valor == null ? 60 : Math.max(1, Long.parseLong(valor.trim()));
        } catch (NumberFormatException ignorado) {
            return 60;
        }
    }

    private String codigoDe(String cuerpo) {
        try {
            return cuerpo == null || cuerpo.isBlank() ? null : mapper.readTree(cuerpo).path("code").asText(null);
        } catch (Exception ignorado) {
            return null;
        }
    }

    record Busqueda(String tipoDocumento, String numeroDocumento) {
    }
}

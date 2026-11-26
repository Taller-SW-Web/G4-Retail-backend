package pe.edu.unmsm.fisi.retail.integracion.productos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.CampanaComercial;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Adaptador real de campañas de G6: {@code GET /promociones?canal=RETAIL&vigentes=true} (scope
 * {@code promociones:leer}, contrato v0.5 §4.2 y §5.11).
 *
 * <p>El {@code PromocionResumen} de G6 trae nombre, estado, vigencia y canales; el tipo de descuento y
 * el cupón no vienen, así que viajan en {@code null}. Solo se toman las {@code ACTIVA}.</p>
 */
@Slf4j
public class CampanasHttp implements CampanasPort {

    private final RestClient restClient;
    private final TokenServicioG7 tokenServicio;

    public CampanasHttp(RestClient restClient, TokenServicioG7 tokenServicio) {
        this.restClient = restClient;
        this.tokenServicio = tokenServicio;
    }

    @Override
    public List<CampanaComercial> listarCampanas() {
        try {
            Respuesta r = restClient.get()
                    .uri(uri -> uri.path("/promociones").queryParam("canal", "RETAIL").queryParam("vigentes", true).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenServicio.obtener())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Respuesta.class);
            if (r == null || r.items() == null) {
                return List.of();
            }
            return r.items().stream()
                    .filter(p -> "ACTIVA".equals(p.status()))
                    .map(p -> new CampanaComercial(p.promotionId(), p.name(), null,
                            p.channels() == null ? Set.of() : Set.copyOf(p.channels()),
                            null, null, null, null, null, p.validFrom(), p.validUntil()))
                    .toList();
        } catch (RestClientException e) {
            log.warn("Promociones (G6) no respondió al listar campañas: {}", e.getMessage());
            throw new ServicioExternoNoDisponibleException("PRODUCTOS_NO_DISPONIBLE",
                    "Productos no respondió al listar las campañas vigentes");
        }
    }

    @Override
    public String origen() {
        return "PRODUCTOS_G6";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Respuesta(@JsonProperty("items") List<PromocionResumen> items) {
    }

    /** {@code PromocionResumen} de G6. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromocionResumen(@JsonProperty("promotion_id") String promotionId,
                            @JsonProperty("name") String name,
                            @JsonProperty("status") String status,
                            @JsonProperty("valid_from") OffsetDateTime validFrom,
                            @JsonProperty("valid_until") OffsetDateTime validUntil,
                            @JsonProperty("channels") List<String> channels) {
    }
}

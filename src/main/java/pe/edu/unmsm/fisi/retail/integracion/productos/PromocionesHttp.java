package pe.edu.unmsm.fisi.retail.integracion.productos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.math.BigDecimal;
import java.util.List;

/**
 * Adaptador real del motor de promociones de G6: {@code POST /promociones/evaluar} (scope
 * {@code promociones:evaluar}, contrato v0.5 §4.2).
 *
 * <p>Envía {@code {channel_id: RETAIL, lines: [{sku, quantity}], coupon_code}} y lee
 * {@code {promotion_id, discount_amount, reason}}. Si llegó un cupón y G6 no devuelve descuento, el
 * cupón se informa como rechazado. Un {@code 422} (contexto inválido, por ejemplo un cupón inexistente)
 * también cuenta como cupón rechazado y no bloquea la venta (RF-11).</p>
 */
@Slf4j
public class PromocionesHttp implements PromocionesPort {

    private final RestClient restClient;
    private final TokenServicioG7 tokenServicio;

    public PromocionesHttp(RestClient restClient, TokenServicioG7 tokenServicio) {
        this.restClient = restClient;
        this.tokenServicio = tokenServicio;
    }

    @Override
    public Evaluacion evaluar(List<Linea> lineas, String cupon) {
        boolean conCupon = cupon != null && !cupon.isBlank();
        Peticion cuerpo = new Peticion(ProductosHttp.CANAL,
                lineas.stream().map(l -> new LineaG6(l.sku(), l.cantidad())).toList(),
                conCupon ? cupon : null);
        try {
            Respuesta r = restClient.post()
                    .uri("/promociones/evaluar")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenServicio.obtener())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cuerpo)
                    .retrieve()
                    .body(Respuesta.class);
            if (r == null || r.discountAmount() == null || r.discountAmount().signum() <= 0) {
                return Evaluacion.sinBeneficio(conCupon);
            }
            String nombre = conCupon ? "Cupón " + cupon : "Promoción " + r.promotionId();
            return new Evaluacion(r.promotionId(), nombre, r.discountAmount(), false);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 422) {
                log.info("Promociones (G6) rechazó el contexto de evaluación (cupón={}): {}", cupon, e.getResponseBodyAsString());
                return Evaluacion.sinBeneficio(conCupon);
            }
            log.warn("Promociones (G6) respondió {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new ServicioExternoNoDisponibleException("PROMOCIONES_NO_DISPONIBLES",
                    "Productos no pudo evaluar las promociones");
        } catch (RestClientException e) {
            log.warn("Promociones (G6) no respondió: {}", e.getMessage());
            throw new ServicioExternoNoDisponibleException("PROMOCIONES_NO_DISPONIBLES",
                    "Productos no respondió a tiempo al evaluar las promociones");
        }
    }

    @Override
    public String origen() {
        return "PRODUCTOS_G6";
    }

    /** {@code EvaluacionPromocionesRequest} de G6. */
    record Peticion(@JsonProperty("channel_id") String channelId,
                    @JsonProperty("lines") List<LineaG6> lines,
                    @JsonProperty("coupon_code") String couponCode) {
    }

    /** {@code LineaCompra} de G6. */
    record LineaG6(@JsonProperty("sku") String sku, @JsonProperty("quantity") int quantity) {
    }

    /** {@code EvaluacionPromocionesResponse} de G6. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Respuesta(@JsonProperty("promotion_id") String promotionId,
                     @JsonProperty("discount_amount") BigDecimal discountAmount,
                     @JsonProperty("reason") String reason) {
    }
}

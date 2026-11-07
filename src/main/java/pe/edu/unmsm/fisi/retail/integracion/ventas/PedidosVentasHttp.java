package pe.edu.unmsm.fisi.retail.integracion.ventas;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.util.Optional;

/**
 * Adaptador HTTP real hacia Ventas (G5) para la consulta de pedidos (F6).
 *
 * <p>Cada llamada lleva {@code Authorization: Bearer} con el token de servicio de Seguridad (G7);
 * G5 exige el scope {@code ventas:pedidos:consultar}.</p>
 *
 * <p>Un {@code 404} (o {@code 400} por un id mal formado) se lee como "no existe". Un {@code 401} o
 * {@code 403} es {@code 503 VENTAS_CREDENCIAL_RECHAZADA}; una caída, un timeout o un {@code 5xx},
 * {@code 503 VENTAS_NO_DISPONIBLE}.</p>
 */
@Slf4j
public class PedidosVentasHttp implements PedidosVentasPort {

    private final RestClient restClient;
    private final TokenServicioG7 tokenServicio;

    public PedidosVentasHttp(RestClient restClient, TokenServicioG7 tokenServicio) {
        this.restClient = restClient;
        this.tokenServicio = tokenServicio;
    }

    @Override
    public Optional<PedidoVentas> consultar(String pedidoId) {
        try {
            PedidoVentas pedido = restClient.get()
                    .uri("/api/v1/pedidos/{pedidoId}", pedidoId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenServicio.obtener())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(PedidoVentas.class);
            return Optional.ofNullable(pedido);
        } catch (RestClientResponseException e) {
            int estado = e.getStatusCode().value();
            if (estado == 404 || estado == 400) {
                return Optional.empty();
            }
            log.warn("Ventas (G5) respondió {} al consultar el pedido {}", estado, pedidoId);
            if (estado == 401 || estado == 403) {
                throw new ServicioExternoNoDisponibleException("VENTAS_CREDENCIAL_RECHAZADA",
                        "Ventas no aceptó la credencial de servicio de Retail");
            }
            throw noDisponible();
        } catch (RestClientException e) {
            log.warn("Ventas (G5) no respondió al consultar el pedido {}: {}", pedidoId, e.getMessage());
            throw noDisponible();
        }
    }

    @Override
    public String origen() {
        return "VENTAS_G5";
    }

    private static ServicioExternoNoDisponibleException noDisponible() {
        return new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE",
                "Ventas no respondió a tiempo. Reintente en unos segundos");
    }
}

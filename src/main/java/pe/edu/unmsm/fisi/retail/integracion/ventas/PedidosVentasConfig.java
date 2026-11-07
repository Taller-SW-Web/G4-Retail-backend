package pe.edu.unmsm.fisi.retail.integracion.ventas;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.time.Duration;

/**
 * Elige de dónde salen los pedidos de la consulta histórica (F6), igual que {@link PickupVentasConfig}.
 *
 * <p>{@code retail.integracion.ventas.pedidos.modo}: {@code auto} (Ventas real si hay secreto de G7;
 * si no, en memoria), {@code g5} (siempre Ventas real; sin secreto responde {@code 503}) o
 * {@code memoria} (siempre los pedidos de ejemplo).</p>
 */
@Slf4j
@Configuration
public class PedidosVentasConfig {

    @Bean
    public PedidosVentasPort pedidosVentasPort(
            @Value("${retail.integracion.ventas.pedidos.modo:auto}") String modo,
            @Value("${retail.integracion.ventas.url:https://g5-servicio-ventas.onrender.com}") String urlVentas,
            @Value("${retail.integracion.ventas.pedidos.timeout-ms:20000}") int timeoutMs,
            TokenServicioG7 tokenServicio) {

        boolean usarVentas = switch (modo.trim().toLowerCase()) {
            case "g5" -> true;
            case "memoria" -> false;
            default -> tokenServicio.configurado();
        };

        if (!usarVentas) {
            log.warn("Consulta de pedidos (F6) con pedidos de ejemplo en memoria (modo={})", modo);
            return new PedidosVentasEnMemoria();
        }

        // Render apaga los servicios sin tráfico: la primera consulta puede tardar varios segundos.
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofMillis(timeoutMs));
        fabrica.setReadTimeout(Duration.ofMillis(timeoutMs));

        log.info("Consulta de pedidos (F6) conectada a Ventas (G5): url={} timeoutMs={}", urlVentas, timeoutMs);
        return new PedidosVentasHttp(RestClient.builder().baseUrl(urlVentas).requestFactory(fabrica).build(),
                tokenServicio);
    }
}

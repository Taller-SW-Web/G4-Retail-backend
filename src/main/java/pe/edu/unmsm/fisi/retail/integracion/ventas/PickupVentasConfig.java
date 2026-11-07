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
 * Elige de dónde salen los pedidos pickup.
 *
 * <p>No depende del perfil {@code mock}: en la nube el backend corre con {@code supabase,mock}
 * porque Productos (G6) sigue simulado, y el pickup debe ir igual contra Ventas real.</p>
 *
 * <p>{@code retail.integracion.ventas.pickup.modo}:</p>
 * <ul>
 *   <li>{@code auto} (por defecto en local y pruebas): Ventas real si hay secreto de G7 configurado;
 *   si no, en memoria.</li>
 *   <li>{@code g5} (por defecto en la nube, perfil {@code supabase}): siempre Ventas real. Si falta el
 *   secreto, la bandeja responde {@code 503 SEGURIDAD_NO_CONFIGURADA}; nunca muestra pedidos de ejemplo
 *   ni emite constancias ficticias.</li>
 *   <li>{@code memoria}: siempre los pedidos de ejemplo.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class PickupVentasConfig {

    @Bean
    public PickupVentasPort pickupVentasPort(
            @Value("${retail.integracion.ventas.pickup.modo:auto}") String modo,
            @Value("${retail.integracion.ventas.url:https://g5-servicio-ventas.onrender.com}") String urlVentas,
            @Value("${retail.integracion.ventas.pickup.timeout-ms:20000}") int timeoutMs,
            TokenServicioG7 tokenServicio) {

        boolean usarVentas = switch (modo.trim().toLowerCase()) {
            case "g5" -> true;
            case "memoria" -> false;
            default -> tokenServicio.configurado();
        };

        if (!usarVentas) {
            log.warn("Pickup (F7) con pedidos de ejemplo en memoria (modo={}, secreto G7 configurado={})",
                    modo, tokenServicio.configurado());
            return new PickupVentasEnMemoria();
        }

        // Render apaga los servicios sin tráfico: el primer pedido puede tardar varios segundos.
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofMillis(timeoutMs));
        fabrica.setReadTimeout(Duration.ofMillis(timeoutMs));

        if (!tokenServicio.configurado()) {
            log.error("Pickup (F7) en modo {} sin G7_CLIENT_SECRET: la bandeja respondera 503 hasta configurarlo", modo);
        }
        log.info("Pickup (F7) conectado a Ventas (G5): url={} timeoutMs={}", urlVentas, timeoutMs);
        return new PickupVentasHttp(RestClient.builder().baseUrl(urlVentas).requestFactory(fabrica).build(), tokenServicio);
    }
}

package pe.edu.unmsm.fisi.retail.integracion.inventario;

import pe.edu.unmsm.fisi.retail.config.gestionapi.CorrelacionSaliente;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;
import pe.edu.unmsm.fisi.retail.service.outbox.DestinoOutbox;

import java.time.Clock;
import java.time.Duration;

/**
 * Elige el destino de las incidencias del outbox (F12 → Inventory de G6). Pasar al real es solo
 * configuración (contrato v0.5, §2.9).
 *
 * <p>{@code retail.integracion.inventario.modo}:</p>
 * <ul>
 *   <li>{@code memoria} (por defecto mientras G6 confirma la ruta): doble en memoria.</li>
 *   <li>{@code g6}: Inventory real en {@code retail.integracion.inventario.url}, con un token de servicio
 *   para la audiencia {@code retail.integracion.g7.audience-productos}.</li>
 * </ul>
 *
 * <p>El token para G6 se crea aquí y no como bean, para no chocar con el {@link TokenServicioG7} de
 * Ventas que ya inyectan los demás adaptadores.</p>
 */
@Slf4j
@Configuration
public class InventarioIncidenciasConfig {

    @Bean
    public DestinoOutbox inventarioIncidencias(
            @Value("${retail.integracion.inventario.modo:memoria}") String modo,
            @Value("${retail.integracion.inventario.url:}") String url,
            @Value("${retail.integracion.inventario.timeout-ms:10000}") int timeoutMs,
            @Value("${retail.integracion.g7.token-url:https://g7-auth-service.onrender.com/api/v1/auth/token}") String tokenUrl,
            @Value("${retail.integracion.g7.client-id:modulo-retail}") String clientId,
            @Value("${retail.integracion.g7.client-secret:}") String clientSecret,
            @Value("${retail.integracion.g7.audience-productos:api-productos}") String audiencia) {

        if (!"g6".equalsIgnoreCase(modo.trim()) || url.isBlank()) {
            log.warn("Incidencias F12 → Inventory simulado en memoria (modo={}, url configurada={})",
                    modo, !url.isBlank());
            return new InventarioIncidenciasEnMemoria();
        }

        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofMillis(timeoutMs));
        fabrica.setReadTimeout(Duration.ofMillis(timeoutMs));
        RestClient clienteToken = RestClient.builder().baseUrl(tokenUrl).requestFactory(fabrica)
                .requestInterceptor(CorrelacionSaliente.INSTANCIA).build();
        TokenServicioG7 token = new TokenServicioG7(clienteToken, clientId, clientSecret, audiencia, Clock.systemUTC());

        log.info("Incidencias F12 → Inventory real de G6: url={} audiencia={}", url, audiencia);
        return new InventarioIncidenciasHttp(RestClient.builder().baseUrl(url).requestFactory(fabrica)
                .requestInterceptor(CorrelacionSaliente.INSTANCIA).build(), token);
    }
}

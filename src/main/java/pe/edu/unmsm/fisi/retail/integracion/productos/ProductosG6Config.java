package pe.edu.unmsm.fisi.retail.integracion.productos;

import pe.edu.unmsm.fisi.retail.config.gestionapi.CorrelacionSaliente;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.time.Clock;
import java.time.Duration;

/**
 * Conecta Retail con Productos y Ofertas (G6) real cuando la aplicación arranca <strong>sin</strong> el
 * perfil {@code mock} (contrato v0.5, §6: pasar al real es solo configuración).
 *
 * <p>Variables: {@code RETAIL_PRODUCTOS_URL} (base que termina en {@code /api/v1}),
 * {@code RETAIL_PRODUCTOS_TIMEOUT_MS} y {@code G7_AUDIENCE_PRODUCTOS}. Catálogo y promociones comparten
 * un token de servicio para la audiencia {@code api-productos}; se crea aquí y no como bean, para no
 * chocar con el {@link TokenServicioG7} de Ventas.</p>
 */
@Slf4j
@Configuration
@Profile("!mock")
public class ProductosG6Config {

    private final String url;
    private final SimpleClientHttpRequestFactory fabrica;
    private final TokenServicioG7 token;

    public ProductosG6Config(
            @Value("${retail.integracion.productos.url:}") String url,
            @Value("${retail.integracion.productos.timeout-ms:10000}") int timeoutMs,
            @Value("${retail.integracion.g7.token-url:https://g7-auth-service.onrender.com/api/v1/auth/token}") String tokenUrl,
            @Value("${retail.integracion.g7.client-id:modulo-retail}") String clientId,
            @Value("${retail.integracion.g7.client-secret:}") String clientSecret,
            @Value("${retail.integracion.g7.audience-productos:api-productos}") String audiencia) {
        if (url.isBlank()) {
            throw new IllegalStateException("Sin el perfil 'mock' hay que configurar RETAIL_PRODUCTOS_URL (Productos G6)");
        }
        this.url = url;
        this.fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofMillis(timeoutMs));
        fabrica.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.token = new TokenServicioG7(RestClient.builder().baseUrl(tokenUrl).requestFactory(fabrica)
                .requestInterceptor(CorrelacionSaliente.INSTANCIA).build(),
                clientId, clientSecret, audiencia, Clock.systemUTC());
        log.info("Productos (G6) real: url={} audiencia={}", url, audiencia);
    }

    @Bean
    public ProductosPort productosPort(
            @Value("${retail.integracion.productos.cache-segundos:60}") long cacheSegundos,
            @Value("${retail.integracion.productos.cache-variantes-segundos:21600}") long cacheVariantesSegundos,
            @Value("${retail.integracion.productos.paralelismo:4}") int paralelismo) {
        return new ProductosHttp(cliente(), token, Clock.systemUTC(), Duration.ofSeconds(cacheSegundos),
                Duration.ofSeconds(cacheVariantesSegundos), paralelismo);
    }

    @Bean
    public PromocionesPort promocionesPort() {
        return new PromocionesHttp(cliente(), token);
    }

    private RestClient cliente() {
        return RestClient.builder().baseUrl(url).requestFactory(fabrica)
                .requestInterceptor(CorrelacionSaliente.INSTANCIA).build();
    }
}

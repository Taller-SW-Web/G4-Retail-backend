package pe.edu.unmsm.fisi.retail.integracion.productos;

import pe.edu.unmsm.fisi.retail.config.gestionapi.CorrelacionSaliente;
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
 * Campañas de G6 reales cuando la aplicación arranca sin el perfil {@code mock} (contrato v0.5, §6).
 * Usa {@code RETAIL_PRODUCTOS_URL} y un token de servicio para la audiencia {@code api-productos}.
 */
@Configuration
@Profile("!mock")
public class CampanasG6Config {

    @Bean
    public CampanasPort campanasPort(
            @Value("${retail.integracion.productos.url:}") String url,
            @Value("${retail.integracion.productos.timeout-ms:10000}") int timeoutMs,
            @Value("${retail.integracion.g7.token-url:https://g7-auth-service.onrender.com/api/v1/auth/token}") String tokenUrl,
            @Value("${retail.integracion.g7.client-id:modulo-retail}") String clientId,
            @Value("${retail.integracion.g7.client-secret:}") String clientSecret,
            @Value("${retail.integracion.g7.audience-productos:api-productos}") String audiencia) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofMillis(timeoutMs));
        fabrica.setReadTimeout(Duration.ofMillis(timeoutMs));
        TokenServicioG7 token = new TokenServicioG7(RestClient.builder().baseUrl(tokenUrl).requestFactory(fabrica)
                .requestInterceptor(CorrelacionSaliente.INSTANCIA).build(),
                clientId, clientSecret, audiencia, Clock.systemUTC());
        return new CampanasHttp(RestClient.builder().baseUrl(url).requestFactory(fabrica)
                .requestInterceptor(CorrelacionSaliente.INSTANCIA).build(), token);
    }
}

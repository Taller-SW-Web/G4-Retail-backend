package pe.edu.unmsm.fisi.retail.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Permite que la SPA del vendedor, servida desde otro origen, consuma la API.
 *
 * <p>Se publica como {@link CorsConfigurationSource} y no como {@code WebMvcConfigurer} porque el
 * filtro de seguridad corre <strong>antes</strong> que Spring MVC: si CORS se configurara solo en
 * MVC, la peticion de verificacion previa del navegador se rechazaria antes de llegar alli.</p>
 *
 * <p>Los origenes se configuran por propiedad para no tocar codigo entre entornos: en local es Vite
 * ({@code http://localhost:5173}) y en la nube, el dominio de Vercel.</p>
 */
@Configuration
public class CorsConfig {

    private final List<String> origenesPermitidos;

    public CorsConfig(@Value("${retail.cors.origenes-permitidos}") String[] origenesPermitidos) {
        this.origenesPermitidos = List.of(origenesPermitidos);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuracion = new CorsConfiguration();
        configuracion.setAllowedOrigins(origenesPermitidos);
        configuracion.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuracion.setAllowedHeaders(List.of("*"));
        configuracion.setExposedHeaders(List.of("X-Correlation-Id"));
        configuracion.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/api/**", configuracion);
        return fuente;
    }
}

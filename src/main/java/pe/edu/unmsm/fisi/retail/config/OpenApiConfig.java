package pe.edu.unmsm.fisi.retail.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Catálogo OpenAPI de la API de Retail (contrato {@code specs/generales/api-contracts.md}).
 *
 * <p>Se publica en {@code /v3/api-docs} y se explora en {@code /swagger-ui.html}. Todas las
 * operaciones de negocio exigen el JWT de acceso que emite Seguridad (G7); desde Swagger UI se
 * prueba con el botón «Authorize».</p>
 */
@Configuration
public class OpenApiConfig {

    static final String ESQUEMA_SEGURIDAD = "bearerG7";

    @Bean
    public OpenAPI apiRetail() {
        return new OpenAPI()
                .info(new Info()
                        .title("API Retail — Módulo de mostrador (G4)")
                        .version("v1")
                        .description("Venta asistida en tienda física: catálogo, clientes, carrito, venta y "
                                + "comprobante, historial de pedidos, pickup, caja, cambios y vales, y "
                                + "contingencia offline. Los errores se responden en application/problem+json.")
                        .contact(new Contact().name("Grupo 4 — Retail · UNMSM TCSW 2026-II")))
                .components(new Components().addSecuritySchemes(ESQUEMA_SEGURIDAD, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Token de acceso RS256 emitido por Seguridad (G7).")))
                // Relativo: detrás del proxy HTTPS de Railway la URL generada saldría como http://
                // y el navegador bloquearía las pruebas desde Swagger UI.
                .servers(List.of(new Server().url("/").description("Mismo origen que esta documentación")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_SEGURIDAD));
    }
}

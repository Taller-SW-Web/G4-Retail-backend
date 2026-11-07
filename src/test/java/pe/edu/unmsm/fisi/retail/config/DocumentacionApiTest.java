package pe.edu.unmsm.fisi.retail.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documentación interactiva de la API (Semana 11, criterio «Diseño de APIs»).
 *
 * <p>El catálogo OpenAPI y Swagger UI se consultan sin token, para revisar el contrato que corre;
 * las operaciones de negocio siguen exigiendo el JWT de Seguridad (G7).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
class DocumentacionApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("El OpenAPI es público y documenta las rutas de Retail con el esquema Bearer de G7")
    void openApiPublicoConRutasYSeguridad() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("API Retail — Módulo de mostrador (G4)"))
                .andExpect(jsonPath("$.paths['/api/v1/retail/ventas'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/retail/cambios/validar/{boletaNumero}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/retail/pickup/{pedidoId}/entrega'].post").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerG7.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerG7.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.security[0].bearerG7").exists());
    }

    @Test
    @DisplayName("Swagger UI se abre sin token en /swagger-ui.html")
    void swaggerUiPublico() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("Las operaciones de negocio siguen protegidas")
    void negocioSigueProtegido() throws Exception {
        mvc.perform(get("/api/v1/retail/perfil")).andExpect(status().isUnauthorized());
    }
}

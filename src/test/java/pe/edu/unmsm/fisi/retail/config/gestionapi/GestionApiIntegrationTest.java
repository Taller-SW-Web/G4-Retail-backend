package pe.edu.unmsm.fisi.retail.config.gestionapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Políticas del API Management de Retail aplicadas a peticiones reales (contrato v0.5, §2.8):
 * versionado, trazabilidad y límite de peticiones con {@code 429}.
 */
@SpringBootTest(properties = {
        "retail.api.limite.peticiones-por-minuto=3",
        "retail.outbox.habilitado=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@DisplayName("API Management — políticas de ms-retail")
class GestionApiIntegrationTest {

    private static final String PERFIL = "/api/v1/retail/perfil";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Toda respuesta lleva X-Api-Version v1 y un X-Correlation-Id generado si no llegó")
    void versionYCorrelacionGenerada() throws Exception {
        mvc.perform(get("/api/v1/retail/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Api-Version", "v1"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    @DisplayName("Reutiliza el X-Correlation-Id recibido y lo pone también en el cuerpo de los errores (401)")
    void correlacionRecibidaEnError() throws Exception {
        String id = "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b";
        mvc.perform(get(PERFIL).header("X-Correlation-Id", id))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", id))
                .andExpect(jsonPath("$.correlationId").value(id));
    }

    @Test
    @DisplayName("Un X-Correlation-Id que no es UUID se reemplaza: G6 rechaza con 400 los que no lo son (§2.8)")
    void correlacionQueNoEsUuidSeReemplaza() throws Exception {
        mvc.perform(get("/api/v1/retail/health").header("X-Correlation-Id", "demo-hito5-001"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    @DisplayName("Un X-Correlation-Id con caracteres no permitidos se reemplaza por uno nuevo")
    void correlacionInvalidaSeReemplaza() throws Exception {
        mvc.perform(get("/api/v1/retail/health").header("X-Correlation-Id", "malo\ninyectado"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    @DisplayName("Al pasar el límite por vendedor responde 429 DEMASIADAS_SOLICITUDES con Retry-After")
    void limitePorVendedor() throws Exception {
        String vendedor = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(PERFIL).with(jwt().jwt(j -> j.subject(vendedor))))
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(2 - i)));
        }

        mvc.perform(get(PERFIL).with(jwt().jwt(j -> j.subject(vendedor))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("DEMASIADAS_SOLICITUDES"))
                .andExpect(jsonPath("$.status").value(429));

        // Otro vendedor no se ve afectado.
        mvc.perform(get(PERFIL).with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(header().string("X-RateLimit-Remaining", "2"));
    }

    @Test
    @DisplayName("El heartbeat /health no cuenta para el límite")
    void healthExento() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/retail/health"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"));
        }
    }
}

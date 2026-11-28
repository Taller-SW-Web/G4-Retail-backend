package pe.edu.unmsm.fisi.retail.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoints de la torre de control (F11, contrato v0.5 §5.11) con el vendedor semilla de la tienda
 * local, el catálogo simulado de G6 y la bandeja pickup en memoria.
 */
@SpringBootTest(properties = "retail.outbox.habilitado=false")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@DisplayName("ControlController — /api/v1/retail/control/*")
class ControlControllerTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String BASE = "/api/v1/retail/control";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Sin token responde 401")
    void sinToken() throws Exception {
        mvc.perform(get(BASE + "/resumen")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un usuario sin perfil de tienda recibe 403")
    void sinPerfil() throws Exception {
        mvc.perform(get(BASE + "/stock-critico").with(jwt().jwt(j -> j.subject("99999999-0000-0000-0000-000000000009"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_USUARIO_SIN_TIENDA"));
    }

    @Test
    @DisplayName("resumen: devuelve los tres contadores y ninguna fuente caída")
    void resumen() throws Exception {
        mvc.perform(get(BASE + "/resumen").with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pickupPendientes", greaterThan(0)))
                .andExpect(jsonPath("$.stockCritico", greaterThan(0)))
                .andExpect(jsonPath("$.campanasVigentes").isNumber())
                .andExpect(jsonPath("$.fuentesNoDisponibles").isEmpty())
                .andExpect(jsonPath("$.actualizadoEn").exists());
    }

    @Test
    @DisplayName("RF-30: pickup-pendientes trae solo LISTO_PARA_RECOJO con su anaquel")
    void pickupPendientes() throws Exception {
        mvc.perform(get(BASE + "/pickup-pendientes").with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].estado", everyItem(is("LISTO_PARA_RECOJO"))))
                .andExpect(jsonPath("$[0].anaquelUbicacion").exists());
    }

    @Test
    @DisplayName("RF-30: el filtro por DNI deja solo al titular")
    void pickupPorDni() throws Exception {
        mvc.perform(get(BASE + "/pickup-pendientes").param("dni", "72345678")
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].clienteDni", everyItem(is("72345678"))));
    }

    @Test
    @DisplayName("RF-30: un DNI que no tiene 8 dígitos responde 400 PARAMETRO_INVALIDO")
    void dniInvalido() throws Exception {
        mvc.perform(get(BASE + "/pickup-pendientes").param("dni", "123")
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @DisplayName("RF-29: stock-critico lista primero los AGOTADO y luego los CRITICO del catálogo simulado")
    void stockCritico() throws Exception {
        mvc.perform(get(BASE + "/stock-critico").with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nivel").value("AGOTADO"))
                .andExpect(jsonPath("$[*].sku", hasItem("ZAPTRA-42-GRI")))
                .andExpect(jsonPath("$[*].sku", hasItem("ZAPVEL-40-AZU")))
                .andExpect(jsonPath("$[*].nivel", everyItem(org.hamcrest.Matchers.oneOf("AGOTADO", "CRITICO"))));
    }

    @Test
    @DisplayName("RF-31: campanas-vigentes no trae campañas vencidas ni de otro canal")
    void campanasVigentes() throws Exception {
        mvc.perform(get(BASE + "/campanas-vigentes").with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].campanaId", not(hasItem("CMP-2026-007"))))
                .andExpect(jsonPath("$[*].campanaId", not(hasItem("CMP-2026-014"))));
    }
}

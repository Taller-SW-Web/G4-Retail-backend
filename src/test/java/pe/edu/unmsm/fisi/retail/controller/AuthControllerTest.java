package pe.edu.unmsm.fisi.retail.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de {@code GET /api/v1/retail/perfil} (F1).
 *
 * <p>Criterios de aceptacion verificados: sin token no se entrega informacion del personal; con un
 * token valido se devuelve el perfil operativo con la forma del contrato; y quien no esta dado de
 * alta en la tienda recibe {@code 403}, no un {@code 200} vacio ni un error generico.</p>
 *
 * <p>Los datos vienen de {@code datos-local.sql}, el extracto del seed de Angie.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
class AuthControllerTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String SUPERVISOR = "bbbbbbbb-0000-0000-0000-000000000004";
    private static final String DESCONOCIDO = "99999999-9999-9999-9999-999999999999";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Sin token responde 401 en formato problem+json")
    void sinTokenResponde401() throws Exception {
        mvc.perform(get("/api/v1/retail/perfil"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TOKEN_INVALIDO"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("Con el token de un vendedor devuelve su perfil de mostrador")
    void devuelveElPerfilDelVendedor() throws Exception {
        mvc.perform(get("/api/v1/retail/perfil").with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value(VENDEDOR))
                .andExpect(jsonPath("$.codigoVendedor").value("VEN-001"))
                .andExpect(jsonPath("$.perfilTienda").value("VENDEDOR"))
                .andExpect(jsonPath("$.activo").value(true));
    }

    @Test
    @DisplayName("Distingue al supervisor, que tiene atribuciones adicionales")
    void distingueAlSupervisor() throws Exception {
        mvc.perform(get("/api/v1/retail/perfil").with(jwt().jwt(j -> j.subject(SUPERVISOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigoVendedor").value("SUP-001"))
                .andExpect(jsonPath("$.perfilTienda").value("SUPERVISOR"));
    }

    @Test
    @DisplayName("Un usuario valido en Seguridad pero sin alta en la tienda recibe 403")
    void usuarioSinPerfilEnLaTiendaRecibe403() throws Exception {
        mvc.perform(get("/api/v1/retail/perfil").with(jwt().jwt(j -> j.subject(DESCONOCIDO))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_USUARIO_SIN_TIENDA"));
    }

    @Test
    @DisplayName("Un token cuyo sub no es un identificador valido se rechaza, no se adivina")
    void tokenConSubInvalidoSeRechaza() throws Exception {
        mvc.perform(get("/api/v1/retail/perfil").with(jwt().jwt(j -> j.subject("no-soy-un-uuid"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOKEN_SIN_USUARIO_VALIDO"));
    }
}

package pe.edu.unmsm.fisi.retail.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas del latido del servicio (F10).
 *
 * <p>Criterio de aceptacion: debe responder <strong>sin token</strong>. Si exigiera autenticacion,
 * un vendedor con la sesion vencida no podria distinguir un servidor caido de un token expirado, que
 * es justo la decision que necesita tomar para pasar a contingencia.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
class SaludControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Responde UP sin necesidad de token")
    void respondeUpSinToken() throws Exception {
        mvc.perform(get("/api/v1/retail/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("UP"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}

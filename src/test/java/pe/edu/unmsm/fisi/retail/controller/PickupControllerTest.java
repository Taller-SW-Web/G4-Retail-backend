package pe.edu.unmsm.fisi.retail.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoints de retiro en tienda (F7): RF-18 y RF-19.
 *
 * <p>Criterios de aceptación verificados: sin token no se entrega nada; la bandeja lista los pedidos
 * pendientes; una entrega válida responde {@code 200 ENTREGADO}; un DNI que no tiene 8 dígitos
 * responde {@code 400}; un pedido en camino o ya entregado responde {@code 409}; y un pedido
 * inexistente responde {@code 404}.</p>
 *
 * <p>El servicio guarda las entregas en memoria, así que cada prueba arranca con un contexto limpio.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PickupControllerTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String ENTREGA_TITULAR =
            "{\"dniRecoge\":\"45678912\",\"nombreRecoge\":\"María González\",\"esTerceroAutorizado\":false}";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Sin token responde 401")
    void sinTokenResponde401() throws Exception {
        mvc.perform(get("/api/v1/retail/pedidos/pickup"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("La bandeja lista los pedidos pickup pendientes de la tienda")
    void bandejaListaPendientes() throws Exception {
        mvc.perform(get("/api/v1/retail/pedidos/pickup").with(conToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.pedidoId == 'PED-2026-08910')].estado").value("LISTO_PARA_RECOJO"));
    }

    @Test
    @DisplayName("Entregar un pedido listo responde 200 con la constancia y lo retira de la bandeja")
    void entregaValidaResponde200() throws Exception {
        mvc.perform(post("/api/v1/retail/pedidos/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADO"))
                .andExpect(jsonPath("$.constanciaId").value("CONST-PED-2026-08910"));

        mvc.perform(get("/api/v1/retail/pedidos/pickup").with(conToken()))
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("Un DNI que no tiene 8 dígitos responde 400")
    void dniInvalidoResponde400() throws Exception {
        mvc.perform(post("/api/v1/retail/pedidos/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"1234\",\"nombreRecoge\":\"Tercero\",\"esTerceroAutorizado\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @DisplayName("Un pedido que aún está en camino responde 409")
    void pedidoEnCaminoResponde409() throws Exception {
        mvc.perform(post("/api/v1/retail/pedidos/PED-2026-08932/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"48765432\",\"nombreRecoge\":\"Lucero Vargas Paz\",\"esTerceroAutorizado\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_LISTO_PARA_RECOJO"));
    }

    @Test
    @DisplayName("Entregar dos veces el mismo pedido responde 409")
    void entregaDobleResponde409() throws Exception {
        mvc.perform(post("/api/v1/retail/pedidos/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/retail/pedidos/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Un pedido inexistente responde 404")
    void pedidoInexistenteResponde404() throws Exception {
        mvc.perform(post("/api/v1/retail/pedidos/PED-2026-99999/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
    }

    private static RequestPostProcessor conToken() {
        return jwt().jwt(token -> token.subject(VENDEDOR));
    }
}

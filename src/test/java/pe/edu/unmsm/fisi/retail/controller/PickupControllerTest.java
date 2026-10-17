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
 * responde {@code 400}; un pedido en camino o ya entregado responde {@code 409}; un código de retiro
 * incorrecto responde {@code 422}; y un pedido inexistente responde {@code 404}. Las rutas son las
 * canónicas del contrato (§5.7).</p>
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
            "{\"dniRecoge\":\"45678912\",\"nombreRecoge\":\"María González\",\"esTerceroAutorizado\":false,\"codigoRetiro\":\"A7K2Q9\"}";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Sin token responde 401")
    void sinTokenResponde401() throws Exception {
        mvc.perform(get("/api/v1/retail/pickup/pendientes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("La bandeja lista los pedidos pickup pendientes de la tienda")
    void bandejaListaPendientes() throws Exception {
        mvc.perform(get("/api/v1/retail/pickup/pendientes").with(conToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.pedidoId == 'PED-2026-08910')].estado").value("LISTO_PARA_RECOJO"));
    }

    @Test
    @DisplayName("Entregar un pedido listo responde 200 con la constancia y lo retira de la bandeja")
    void entregaValidaResponde200() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADO"))
                .andExpect(jsonPath("$.constanciaId").value("CONST-PED-2026-08910"));

        mvc.perform(get("/api/v1/retail/pickup/pendientes").with(conToken()))
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("Un DNI que no tiene 8 dígitos responde 400")
    void dniInvalidoResponde400() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"1234\",\"nombreRecoge\":\"Tercero\",\"esTerceroAutorizado\":true,\"codigoRetiro\":\"A7K2Q9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @DisplayName("Un pedido que aún está en camino responde 409")
    void pedidoEnCaminoResponde409() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08932/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"48765432\",\"nombreRecoge\":\"Lucero Vargas Paz\",\"esTerceroAutorizado\":false,\"codigoRetiro\":\"A7K2Q9\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_LISTO_PARA_RECOJO"));
    }

    @Test
    @DisplayName("Entregar dos veces el mismo pedido responde 409")
    void entregaDobleResponde409() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Un pedido inexistente responde 404")
    void pedidoInexistenteResponde404() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-99999/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Un código de retiro que no corresponde al pedido responde 422 y no lo entrega")
    void codigoRetiroIncorrectoResponde422() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"45678912\",\"nombreRecoge\":\"María González\",\"esTerceroAutorizado\":false,\"codigoRetiro\":\"ZZZZZZ\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CODIGO_RETIRO_INVALIDO"));

        mvc.perform(get("/api/v1/retail/pickup/pendientes").with(conToken()))
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    @DisplayName("Sin código de retiro responde 400")
    void sinCodigoRetiroResponde400() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"45678912\",\"nombreRecoge\":\"María González\",\"esTerceroAutorizado\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    private static RequestPostProcessor conToken() {
        return jwt().jwt(token -> token.subject(VENDEDOR));
    }
}

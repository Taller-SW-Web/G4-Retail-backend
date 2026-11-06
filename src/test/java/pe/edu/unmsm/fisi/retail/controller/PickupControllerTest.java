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
 * pendientes de la tienda; el titular recoge con su DNI; un tercero solo recoge con carta poder;
 * un DNI que no tiene 8 dígitos responde {@code 400}; un pedido en camino responde {@code 409}; un
 * pedido que no está en la bandeja responde {@code 404}. Las rutas son las canónicas del contrato
 * (§5.7).</p>
 *
 * <p>Sin secreto de G7 el modo {@code auto} usa los pedidos de ejemplo en memoria (nunca llama a Ventas
 * real). No se fija la propiedad aqui para compartir el contexto de Spring con las demas pruebas: un
 * contexto propio, al cerrarse, borraria las tablas de la base H2 compartida. Cada prueba arranca con un
 * contexto limpio porque las entregas se guardan en memoria.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PickupControllerTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String SIN_TIENDA = "bbbbbbbb-0000-0000-0000-000000000099";
    private static final String ENTREGA_TITULAR =
            "{\"dniRecoge\":\"45678912\",\"nombreRecoge\":\"María González\",\"esTerceroAutorizado\":false}";

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
    @DisplayName("Un usuario sin perfil en la tienda responde 403")
    void usuarioSinTiendaResponde403() throws Exception {
        mvc.perform(get("/api/v1/retail/pickup/pendientes").with(jwt().jwt(t -> t.subject(SIN_TIENDA))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_USUARIO_SIN_TIENDA"));
    }

    @Test
    @DisplayName("El titular recoge con su DNI: 200 con la constancia y sale de la bandeja")
    void entregaAlTitularResponde200() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADO"))
                .andExpect(jsonPath("$.constanciaId").value("CONST-PED-2026-08910"));

        mvc.perform(get("/api/v1/retail/pickup/pendientes").with(conToken()))
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("Si el DNI no es el del titular responde 422 y el pedido sigue en la bandeja")
    void dniDistintoAlTitularResponde422() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"11112222\",\"nombreRecoge\":\"María González\",\"esTerceroAutorizado\":false}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DNI_NO_COINCIDE_CON_TITULAR"));

        mvc.perform(get("/api/v1/retail/pickup/pendientes").with(conToken()))
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    @DisplayName("Un tercero sin carta poder verificada responde 422")
    void terceroSinCartaPoderResponde422() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"70001111\",\"nombreRecoge\":\"Pedro Apoderado\",\"esTerceroAutorizado\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARTA_PODER_REQUERIDA"));
    }

    @Test
    @DisplayName("Un tercero con carta poder verificada recoge: 200")
    void terceroConCartaPoderResponde200() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08914/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"70001111\",\"nombreRecoge\":\"Pedro Apoderado\",\"esTerceroAutorizado\":true,\"cartaPoderVerificada\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADO"));
    }

    @Test
    @DisplayName("Un DNI que no tiene 8 dígitos responde 400")
    void dniInvalidoResponde400() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"1234\",\"nombreRecoge\":\"Tercero\",\"esTerceroAutorizado\":true,\"cartaPoderVerificada\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @DisplayName("Un código de retiro mal formado responde 400")
    void codigoRetiroMalFormadoResponde400() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"45678912\",\"nombreRecoge\":\"María González\",\"esTerceroAutorizado\":false,\"codigoRetiro\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @DisplayName("Un pedido que aún está en camino responde 409")
    void pedidoEnCaminoResponde409() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08932/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dniRecoge\":\"48765432\",\"nombreRecoge\":\"Lucero Vargas Paz\",\"esTerceroAutorizado\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_LISTO_PARA_RECOJO"));
    }

    @Test
    @DisplayName("Un pedido ya entregado ya no figura en la bandeja: 404")
    void entregaDobleResponde404() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/retail/pickup/PED-2026-08910/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Un pedido inexistente responde 404")
    void pedidoInexistenteResponde404() throws Exception {
        mvc.perform(post("/api/v1/retail/pickup/PED-2026-99999/entrega").with(conToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ENTREGA_TITULAR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
    }

    private static RequestPostProcessor conToken() {
        return jwt().jwt(token -> token.subject(VENDEDOR));
    }
}

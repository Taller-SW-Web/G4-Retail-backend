package pe.edu.unmsm.fisi.retail.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import pe.edu.unmsm.fisi.retail.integracion.ventas.RegistroPedidosMostrador;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoints de consulta histórica de pedidos (F6): RF-16 y RF-17, rutas del contrato §5.6.
 *
 * <p>Sin secreto de G7 el modo {@code auto} usa los pedidos de ejemplo en memoria (nunca llama a
 * Ventas real). Misma configuración que las demás pruebas MockMvc para compartir el contexto.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
class HistorialPedidosControllerTest {

    private static final String RUTA = "/api/v1/retail/pedidos";
    private static final String SIN_TIENDA = "bbbbbbbb-0000-0000-0000-000000000099";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RegistroPedidosMostrador registro;

    private static RequestPostProcessor conToken() {
        return jwt().jwt(t -> t.subject("bbbbbbbb-0000-0000-0000-000000000001"));
    }

    @Test
    @DisplayName("Sin token responde 401")
    void sinTokenResponde401() throws Exception {
        mvc.perform(get(RUTA).param("documentoCliente", "72345678"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un usuario sin perfil en la tienda responde 403 en la búsqueda")
    void busquedaSinPerfilResponde403() throws Exception {
        mvc.perform(get(RUTA).param("codigoPedido", "PED-2026-00405").with(jwt().jwt(t -> t.subject(SIN_TIENDA))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_USUARIO_SIN_TIENDA"));
    }

    @Test
    @DisplayName("Un usuario sin perfil en la tienda responde 403 en el detalle")
    void detalleSinPerfilResponde403() throws Exception {
        mvc.perform(get(RUTA + "/PED-2026-00405").with(jwt().jwt(t -> t.subject(SIN_TIENDA))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_USUARIO_SIN_TIENDA"));
    }

    @Test
    @DisplayName("RF-16: por DNI lista las ventas de esta ejecución del más reciente al más antiguo")
    void porDniListaOrdenado() throws Exception {
        registro.registrar("72345678", "PED-2026-00377");
        registro.registrar("72345678", "PED-2026-00412");
        registro.registrar("72345678", "PED-2026-00398");

        mvc.perform(get(RUTA).param("documentoCliente", "72345678").with(conToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].pedidoId").value("PED-2026-00412"))
                .andExpect(jsonPath("$.items[1].pedidoId").value("PED-2026-00398"))
                .andExpect(jsonPath("$.items[2].pedidoId").value("PED-2026-00377"))
                .andExpect(jsonPath("$.items[0].estadoComercial").value("ENTREGADO"))
                .andExpect(jsonPath("$.items[0].fechaCreacion").value(endsWith("-05:00")));
    }

    @Test
    @DisplayName("Por DNI/RUC no busca el histórico: sin ventas en esta ejecución responde 404")
    void porDocumentoSinVentasEnEstaEjecucion() throws Exception {
        mvc.perform(get(RUTA).param("documentoCliente", "20123456789").with(conToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("RF-16: por código existente responde 200 con ese pedido")
    void porCodigoExistente() throws Exception {
        mvc.perform(get(RUTA).param("codigoPedido", "PED-2026-00405").with(conToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].canal").value("MARKETPLACE"))
                .andExpect(jsonPath("$.items[0].estadoComercial").value("EN_PREPARACION"));
    }

    @Test
    @DisplayName("RF-16: un código erróneo responde 404 PEDIDO_NO_ENCONTRADO")
    void codigoErroneoResponde404() throws Exception {
        mvc.perform(get(RUTA).param("codigoPedido", "PED-2026-99999").with(conToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Sin filtros responde 400 PARAMETRO_OBLIGATORIO")
    void sinFiltrosResponde400() throws Exception {
        mvc.perform(get(RUTA).with(conToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_OBLIGATORIO"));
    }

    @Test
    @DisplayName("Un documento que no es DNI ni RUC responde 400 PARAMETRO_INVALIDO")
    void documentoMalFormadoResponde400() throws Exception {
        mvc.perform(get(RUTA).param("documentoCliente", "7234").with(conToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"))
                .andExpect(jsonPath("$.errores[0].campo").value("documentoCliente"));
    }

    @Test
    @DisplayName("Un size que no es número responde 400 PARAMETRO_INVALIDO")
    void sizeNoNumericoResponde400() throws Exception {
        mvc.perform(get(RUTA).param("documentoCliente", "72345678").param("size", "abc").with(conToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @DisplayName("RF-17: el detalle trae prendas e historial en orden cronológico, incluida la anulación")
    void detalleConHistorial() throws Exception {
        mvc.perform(get(RUTA + "/PED-2026-00377").with(conToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoComercial").value("ANULADO"))
                .andExpect(jsonPath("$.canal").value("CHATBOT"))
                .andExpect(jsonPath("$.lineas[0].sku").value("BAL-FUT-5-BLANC"))
                .andExpect(jsonPath("$.historialEstados.length()").value(3))
                .andExpect(jsonPath("$.historialEstados[0].estado").value("CREADO"))
                .andExpect(jsonPath("$.historialEstados[2].estado").value("ANULADO"))
                .andExpect(jsonPath("$.historialEstados[2].motivo").value("Solicitado por el cliente"))
                .andExpect(jsonPath("$.historialEstados[2].fecha").value(endsWith("-05:00")));
    }

    @Test
    @DisplayName("RF-17: un pedido pickup trae la tienda de recojo")
    void detallePickupTraeTienda() throws Exception {
        mvc.perform(get(RUTA + "/PED-2026-00405").with(conToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modalidad").value("RECOJO_TIENDA"))
                .andExpect(jsonPath("$.tiendaId").value("TIENDA-MIRAFLORES"));
    }

    @Test
    @DisplayName("RF-17: el detalle de un pedido inexistente responde 404")
    void detalleInexistenteResponde404() throws Exception {
        mvc.perform(get(RUTA + "/PED-2026-99999").with(conToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
    }
}

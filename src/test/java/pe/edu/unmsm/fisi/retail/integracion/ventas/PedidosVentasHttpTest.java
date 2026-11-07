package pe.edu.unmsm.fisi.retail.integracion.ventas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("PedidosVentasHttp — consulta de pedidos contra Ventas (G5)")
class PedidosVentasHttpTest {

    private static final String BASE = "https://g5-servicio-ventas.onrender.com";

    /** Respuesta real de G5 (PedidoDetalle), con campos que Retail no usa. */
    private static final String PEDIDO_G5 = """
            {"pedidoId":"PED-2026-00412","canal":"RETAIL","estado":"PAGADO","tipo":"VENTA",
             "contacto":{"clienteId":"8d4e0c3a-91b2-4f1e-a0c7-5e2b7f6d9a10","nombreCompleto":"Juan Pérez",
                         "tipoDocumento":"DNI","numeroDocumento":"72345678","email":"x@y.pe","telefono":"999"},
             "pago":{"total":489.80,"moneda":"PEN","metodoPago":"EFECTIVO","estado":"CONFIRMADO"},
             "envio":{"modalidad":"TIENDA","tiendaId":"TIENDA-MIRAFLORES","direccion":"Tienda Mostrador"},
             "items":[{"productoId":"PROD-1","sku":"ZAP-RUN-41-BLANC","descripcion":"Zapatilla","cantidad":1,"precioUnitario":459.90}],
             "fechaCreacion":"2026-11-06T15:30:00Z","ultimaActualizacion":"2026-11-06T15:30:00Z",
             "historialEstados":[{"estado":"PAGADO","fecha":"2026-11-06T15:30:00Z","actor":"SISTEMA","motivo":null}]}
            """;

    private MockRestServiceServer servidor;
    private RegistroPedidosMostrador registro;
    private PedidosVentasHttp adaptador;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.obtener()).thenReturn("token-de-servicio");
        registro = new RegistroPedidosMostrador();
        adaptador = new PedidosVentasHttp(builder.build(), token, registro);
    }

    @Test
    @DisplayName("Consulta el pedido con Bearer y lee el formato de G5")
    void consultaPedidoConToken() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/PED-2026-00412"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-de-servicio"))
                .andRespond(withSuccess(PEDIDO_G5, MediaType.APPLICATION_JSON));

        Optional<PedidoVentas> pedido = adaptador.consultar("PED-2026-00412");

        assertThat(pedido).isPresent();
        assertThat(pedido.get().estado()).isEqualTo("PAGADO");
        assertThat(pedido.get().contacto().numeroDocumento()).isEqualTo("72345678");
        assertThat(pedido.get().items().get(0).sku()).isEqualTo("ZAP-RUN-41-BLANC");
        assertThat(pedido.get().historialEstados()).hasSize(1);
        servidor.verify();
    }

    @Test
    @DisplayName("Un 404 de G5 se lee como pedido inexistente")
    void noEncontradoDevuelveVacio() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/PED-2026-99999"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"codigo\":\"PEDIDO_NO_ENCONTRADO\",\"mensaje\":\"No existe\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(adaptador.consultar("PED-2026-99999")).isEmpty();
    }

    @Test
    @DisplayName("Un 403 de G5 es 503 VENTAS_CREDENCIAL_RECHAZADA")
    void credencialRechazada() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/PED-2026-00412"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adaptador.consultar("PED-2026-00412"))
                .isInstanceOf(ServicioExternoNoDisponibleException.class)
                .extracting("codigo").isEqualTo("VENTAS_CREDENCIAL_RECHAZADA");
    }

    @Test
    @DisplayName("Un 5xx de G5 es 503 VENTAS_NO_DISPONIBLE")
    void caidaDeVentas() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/PED-2026-00412"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adaptador.consultar("PED-2026-00412"))
                .isInstanceOf(ServicioExternoNoDisponibleException.class)
                .extracting("codigo").isEqualTo("VENTAS_NO_DISPONIBLE");
    }

    @Test
    @DisplayName("Por documento consulta en G5 cada pedido registrado y omite los que G5 ya no conoce")
    void buscaPorDocumentoConElRegistroLocal() {
        registro.registrar("72345678", "PED-2026-00412");
        registro.registrar("72345678", "VNT-20261106-00001");
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/PED-2026-00412"))
                .andRespond(withSuccess(PEDIDO_G5, MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/VNT-20261106-00001"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        List<PedidoVentas> pedidos = adaptador.buscarPorDocumento("72345678");

        assertThat(pedidos).extracting(PedidoVentas::pedidoId).containsExactly("PED-2026-00412");
        servidor.verify();
    }

    @Test
    @DisplayName("Un documento sin pedidos registrados no llama a G5")
    void documentoSinPedidos() {
        assertThat(adaptador.buscarPorDocumento("40404040")).isEmpty();
        servidor.verify();
    }
}

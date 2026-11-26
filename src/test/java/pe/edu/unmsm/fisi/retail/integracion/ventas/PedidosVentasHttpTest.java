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
    private PedidosVentasHttp adaptador;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.obtener()).thenReturn("token-de-servicio");
        adaptador = new PedidosVentasHttp(builder.build(), token);
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

    // ---- RF-16-DOC: GET /api/v1/pedidos?buscar= (contrato v0.5.2, §4.3 y §5.6) ----

    @Test
    @DisplayName("RF-16-DOC: busca por documento con ?buscar= y lee los campos que acordó G5")
    void buscaPorDocumento() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos?buscar=72345678"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-de-servicio"))
                .andRespond(withSuccess("""
                        {"items":[{"pedidoId":"PED-2026-00412","canal":"RETAIL","estado":"PAGADO","total":489.80,
                                   "moneda":"PEN","fechaCreacion":"2026-11-06T15:30:00Z","nombreCliente":"Juan Pérez"}],
                         "meta":{"total":1}}
                        """, MediaType.APPLICATION_JSON));

        List<PedidoResumenVentas> resultado = adaptador.buscarPorDocumento("72345678");

        assertThat(resultado).singleElement().satisfies(r -> {
            assertThat(r.pedidoId()).isEqualTo("PED-2026-00412");
            assertThat(r.estado()).isEqualTo("PAGADO");
            assertThat(r.total()).isEqualByComparingTo("489.80");
            assertThat(r.nombreCliente()).isEqualTo("Juan Pérez");
        });
        servidor.verify();
    }

    @Test
    @DisplayName("RF-16-DOC: acepta también una respuesta en arreglo")
    void buscaPorDocumentoConArreglo() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos?buscar=20123456789"))
                .andRespond(withSuccess("""
                        [{"pedidoId":"PED-2026-00415","canal":"RETAIL","estado":"PAGADO","total":1159.60,
                          "moneda":"PEN","fechaCreacion":"2026-11-07T10:00:00Z","nombreCliente":"Club Los Andes"}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(adaptador.buscarPorDocumento("20123456789")).extracting(PedidoResumenVentas::pedidoId)
                .containsExactly("PED-2026-00415");
    }

    @Test
    @DisplayName("RF-16-DOC: un 404 de Ventas es una búsqueda sin resultados")
    void buscaPorDocumentoSinResultados() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos?buscar=11111111")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adaptador.buscarPorDocumento("11111111")).isEmpty();
    }

    @Test
    @DisplayName("RF-16-DOC: sin el scope ventas:pedidos:buscar-documento (403) responde VENTAS_CREDENCIAL_RECHAZADA")
    void buscaPorDocumentoSinScope() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos?buscar=72345678")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adaptador.buscarPorDocumento("72345678"))
                .isInstanceOf(ServicioExternoNoDisponibleException.class)
                .extracting("codigo").isEqualTo("VENTAS_CREDENCIAL_RECHAZADA");
    }

    @Test
    @DisplayName("RF-16-DOC: un 5xx de Ventas responde VENTAS_NO_DISPONIBLE")
    void buscaPorDocumentoVentasCaido() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos?buscar=72345678")).andRespond(withServerError());

        assertThatThrownBy(() -> adaptador.buscarPorDocumento("72345678"))
                .isInstanceOf(ServicioExternoNoDisponibleException.class)
                .extracting("codigo").isEqualTo("VENTAS_NO_DISPONIBLE");
    }
}

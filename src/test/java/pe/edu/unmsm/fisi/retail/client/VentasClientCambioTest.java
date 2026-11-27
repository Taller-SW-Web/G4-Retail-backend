package pe.edu.unmsm.fisi.retail.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;
import pe.edu.unmsm.fisi.retail.integracion.cambios.ResultadoPedidoCambio;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pedido del cambio directo contra Ventas (F9, contrato 5.9): un rechazo de Ventas (4xx) no se
 * reintenta y una caida (5xx o sin respuesta) queda pendiente para el reintento.
 */
@DisplayName("VentasClient — pedido del cambio directo (F9)")
class VentasClientCambioTest {

    private static final String URL = "https://ventas.test/api/v1/pedidos";
    private static final String CLAVE = "cambio-clave-1";

    private MockRestServiceServer servidor;
    private VentasClient cliente;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new VentasClient(builder.baseUrl("https://ventas.test").build(), true);
    }

    private static VentasCrearPedidoRequest pedido() {
        return new VentasCrearPedidoRequest("RETAIL", null,
                List.of(new VentasCrearPedidoRequest.Item("PROD-001", "SKU-001", "Prenda", 1, new BigDecimal("120.00"))),
                null, null, null);
    }

    @Test
    @DisplayName("Ventas crea el pedido: queda Confirmado con su pedidoId y la misma Idempotency-Key")
    void confirmado() {
        servidor.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", CLAVE))
                .andRespond(withSuccess("{\"pedidoId\":\"PED-2026-00500\",\"estado\":\"PAGADO\"}",
                        MediaType.APPLICATION_JSON));

        ResultadoPedidoCambio resultado = cliente.crearPedidoCambio(pedido(), CLAVE);

        assertThat(resultado).isEqualTo(new ResultadoPedidoCambio.Confirmado("PED-2026-00500", "PAGADO"));
        servidor.verify();
    }

    @Test
    @DisplayName("Ventas responde sin pedidoId: no se da por confirmado, queda NoDisponible")
    void sinPedidoId() {
        servidor.expect(requestTo(URL))
                .andRespond(withSuccess("{\"estado\":\"PAGADO\"}", MediaType.APPLICATION_JSON));

        assertThat(cliente.crearPedidoCambio(pedido(), CLAVE)).isInstanceOf(ResultadoPedidoCambio.NoDisponible.class);
    }

    @Test
    @DisplayName("Ventas rechaza con 422 y code: queda Rechazado con ese code, sin reintento")
    void rechazoConCodigo() {
        servidor.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"PEDIDO_ORIGEN_NO_ENTREGADO\",\"detail\":\"El pedido no fue entregado\"}"));

        ResultadoPedidoCambio resultado = cliente.crearPedidoCambio(pedido(), CLAVE);

        assertThat(resultado).isInstanceOf(ResultadoPedidoCambio.Rechazado.class);
        assertThat(((ResultadoPedidoCambio.Rechazado) resultado).codigo()).isEqualTo("PEDIDO_ORIGEN_NO_ENTREGADO");
    }

    @Test
    @DisplayName("Ventas rechaza con 400 sin cuerpo JSON: el code queda RECHAZADO_POR_VENTAS")
    void rechazoSinCodigo() {
        servidor.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body("error"));

        ResultadoPedidoCambio resultado = cliente.crearPedidoCambio(pedido(), CLAVE);

        assertThat(((ResultadoPedidoCambio.Rechazado) resultado).codigo()).isEqualTo("RECHAZADO_POR_VENTAS");
    }

    @Test
    @DisplayName("Ventas responde 503: queda NoDisponible para el reintento")
    void caida5xx() {
        servidor.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(cliente.crearPedidoCambio(pedido(), CLAVE)).isInstanceOf(ResultadoPedidoCambio.NoDisponible.class);
    }

    @Test
    @DisplayName("Ventas no responde: queda NoDisponible para el reintento")
    void sinRespuesta() {
        servidor.expect(requestTo(URL)).andRespond(withException(new IOException("timeout")));

        assertThat(cliente.crearPedidoCambio(pedido(), CLAVE)).isInstanceOf(ResultadoPedidoCambio.NoDisponible.class);
    }

    @Test
    @DisplayName("Con la integracion deshabilitada no llama a Ventas y queda NoDisponible")
    void deshabilitado() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer sinLlamadas = MockRestServiceServer.bindTo(builder).build();
        VentasClient apagado = new VentasClient(builder.baseUrl("https://ventas.test").build(), false);

        assertThat(apagado.crearPedidoCambio(pedido(), CLAVE)).isInstanceOf(ResultadoPedidoCambio.NoDisponible.class);
        sinLlamadas.verify();
    }
}

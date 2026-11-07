package pe.edu.unmsm.fisi.retail.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;
import pe.edu.unmsm.fisi.retail.client.dto.VentasPedidoResponse;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("VentasClient — Pruebas de Integración HTTP con Módulo de Ventas G5")
class VentasClientTest {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private VentasClient ventasClient;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        RestClient restClient = restClientBuilder.baseUrl("https://g5-servicio-ventas.onrender.com").build();
        ventasClient = new VentasClient(restClient, true);
    }

    private VentasCrearPedidoRequest construirRequestValido() {
        return new VentasCrearPedidoRequest(
                "RETAIL",
                new VentasCrearPedidoRequest.Contacto(
                        UUID.fromString("00000000-0000-4000-8000-00000000f00a"),
                        "Cliente Prueba",
                        "DNI",
                        "12345678",
                        "999888777",
                        "cliente@prueba.com"
                ),
                List.of(new VentasCrearPedidoRequest.Item("PROD-001", "SKU-001", "Prenda", 1, new BigDecimal("100.00"))),
                null,
                new VentasCrearPedidoRequest.Envio("TIENDA", BigDecimal.ZERO, "Cliente Prueba", "Lima", "Lima", "Miraflores", "Tienda", "POS-01", "TIENDA-01"),
                new VentasCrearPedidoRequest.Pago("EFECTIVO", "PEN", new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"), null, new BigDecimal("100.00"), BigDecimal.ZERO, OffsetDateTime.now())
        );
    }

    @Test
    @DisplayName("Cuando Ventas responde 201 Created con pedidoId, retorna Optional con la respuesta")
    void crearPedidoExitoso() throws Exception {
        VentasPedidoResponse responseG5 = new VentasPedidoResponse(
                "PED-2026-00091",
                "PAGADO",
                new BigDecimal("100.00"),
                "PEN",
                "RETAIL",
                OffsetDateTime.now()
        );

        mockServer.expect(requestTo("https://g5-servicio-ventas.onrender.com/api/v1/pedidos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "key-123"))
                .andRespond(withSuccess(mapper.writeValueAsString(responseG5), MediaType.APPLICATION_JSON));

        Optional<VentasPedidoResponse> resultado = ventasClient.crearPedido(construirRequestValido(), "key-123");

        assertThat(resultado).isPresent();
        assertThat(resultado.get().pedidoId()).isEqualTo("PED-2026-00091");
        assertThat(resultado.get().estado()).isEqualTo("PAGADO");
        mockServer.verify();
    }

    @Test
    @DisplayName("Envía el contrato RETAIL con el comprobante separado del voucher")
    void crearPedidoRetailConComprobante() throws Exception {
        String clave = "a2a147cb-65fa-4cb4-88e7-2def99a80c36";
        String payload = """
                {
                  "canal": "RETAIL",
                  "contacto": {
                    "clienteId": "11111111-1111-4111-8111-111111111111",
                    "nombreCompleto": "Cliente Prueba",
                    "tipoDocumento": "DNI",
                    "numeroDocumento": "12345678",
                    "email": "cliente@example.com",
                    "telefono": "+51999999999"
                  },
                  "items": [{
                    "productoId": "PROD-001",
                    "sku": "SKU-001",
                    "descripcion": "Prenda",
                    "cantidad": 1,
                    "precioUnitario": 199.90
                  }],
                  "envio": {"tiendaId": "TIENDA-MIRAFLORES"},
                  "pago": {
                    "metodoPago": "TARJETA_POS",
                    "moneda": "PEN",
                    "subtotal": 199.90,
                    "descuentoCupon": 0.00,
                    "costoEnvio": 0.00,
                    "total": 199.90,
                    "referenciaOperacion": "VOU-987654",
                    "comprobanteReferencia": "B001-00045678"
                  }
                }
                """;
        VentasCrearPedidoRequest pedido = mapper.readValue(payload, VentasCrearPedidoRequest.class);
        mockServer.expect(requestTo("https://g5-servicio-ventas.onrender.com/api/v1/pedidos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", clave))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(payload, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"pedidoId":"PED-2026-00091","estado":"ENTREGADO","total":199.90,"moneda":"PEN"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(ventasClient.crearPedido(pedido, clave)).hasValueSatisfying(respuesta -> {
            assertThat(respuesta.pedidoId()).isEqualTo("PED-2026-00091");
            assertThat(respuesta.estado()).isEqualTo("ENTREGADO");
        });
        mockServer.verify();
    }

    @Test
    @DisplayName("Cuando Ventas responde con error 500 o falla de red, retorna Optional.empty() para activar fallback")
    void crearPedidoFallaServidorRetornaEmpty() {
        mockServer.expect(requestTo("https://g5-servicio-ventas.onrender.com/api/v1/pedidos"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        Optional<VentasPedidoResponse> resultado = ventasClient.crearPedido(construirRequestValido(), "key-123");

        assertThat(resultado).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("Cuando la integración está deshabilitada (habilitado=false), retorna Optional.empty() sin hacer peticiones")
    void crearPedidoDeshabilitadoRetornaEmpty() {
        VentasClient clienteDeshabilitado = new VentasClient(restClientBuilder.build(), false);
        Optional<VentasPedidoResponse> resultado = clienteDeshabilitado.crearPedido(construirRequestValido(), "key-123");

        assertThat(resultado).isEmpty();
    }
}

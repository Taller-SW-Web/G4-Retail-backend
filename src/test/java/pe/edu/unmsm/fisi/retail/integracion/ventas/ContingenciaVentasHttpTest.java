package pe.edu.unmsm.fisi.retail.integracion.ventas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest.Item;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest.VentaOffline;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;
import pe.edu.unmsm.fisi.retail.service.CodigosSucursal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("ContingenciaVentasHttp — venta offline enviada a Ventas (G5)")
class ContingenciaVentasHttpTest {

    private static final String BASE = "https://g5-api-gateway.onrender.com";

    private MockRestServiceServer servidor;
    private ContingenciaVentasHttp adaptador;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.configurado()).thenReturn(true);
        when(token.obtener()).thenReturn("token-de-servicio");
        adaptador = new ContingenciaVentasHttp(builder.build(), token, new CodigosSucursal(""));
    }

    @Test
    @DisplayName("Contrato §5.10 v0.5.9: la venta sin cliente viaja con el clienteId genérico de \"Clientes varios\"")
    void enviaClienteIdGenerico() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.canal").value("RETAIL"))
                .andExpect(jsonPath("$.contacto.clienteId").value("00000000-0000-4000-8000-00000000f00a"))
                .andRespond(withSuccess(
                        "{\"pedidoId\":\"PED-2026-01005\",\"estado\":\"ENTREGADO\",\"total\":129.90,\"moneda\":\"PEN\",\"canal\":\"RETAIL\"}",
                        MediaType.APPLICATION_JSON));

        VentaOffline venta = new VentaOffline(
                UUID.fromString("c3b9e4a1-0001-49b2-8f3c-2d1e0a9b7c65"),
                "CONT-TIENDA01-00012",
                "2026-10-11T14:20:00Z",
                List.of(new Item("CAM-RUN-M-AZUL", 1, new BigDecimal("129.90"))),
                new BigDecimal("129.90"),
                new BigDecimal("150.00"),
                new BigDecimal("20.10"),
                null,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        String pedidoId = adaptador.registrar(venta, "TIENDA-MIRAFLORES", venta.ventaLocalUuid().toString(), "B010-00000001");

        assertThat(pedidoId).isEqualTo("PED-2026-01005");
        servidor.verify();
    }
}

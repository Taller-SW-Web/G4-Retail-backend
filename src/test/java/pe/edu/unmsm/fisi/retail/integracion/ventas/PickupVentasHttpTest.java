package pe.edu.unmsm.fisi.retail.integracion.ventas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupRequest;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("PickupVentasHttp — bandeja y entrega pickup contra Ventas (G5)")
class PickupVentasHttpTest {

    private static final String BASE = "https://g5-servicio-ventas.onrender.com";

    private MockRestServiceServer servidor;
    private PickupVentasHttp adaptador;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.obtener()).thenReturn("token-de-servicio");
        adaptador = new PickupVentasHttp(builder.build(), token);
    }

    @Test
    @DisplayName("La bandeja se pide con Bearer y tiendaId, y se lee con el formato de G5")
    void listaBandejaConToken() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/pickup?tiendaId=TIENDA-MIRAFLORES"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-de-servicio"))
                .andRespond(withSuccess("""
                        [{"pedidoId":"PED-2026-PICKUP01","clienteNombre":"Carlos Mendoza","clienteDni":"72345678",
                          "anaquelUbicacion":"Estante A-12","fechaArriboTienda":"2026-11-06T02:39:03Z",
                          "estado":"LISTO_PARA_RECOJO",
                          "articulos":[{"sku":"SKU-ZAP-RUN-42","nombre":"Zapatillas Running Pro","talla":"-","color":"-","cantidad":1}]}]
                        """, MediaType.APPLICATION_JSON));

        List<PedidoPickupResponse> pedidos = adaptador.listarPendientes("TIENDA-MIRAFLORES");

        assertThat(pedidos).hasSize(1);
        assertThat(pedidos.get(0).pedidoId()).isEqualTo("PED-2026-PICKUP01");
        assertThat(pedidos.get(0).articulos().get(0).sku()).isEqualTo("SKU-ZAP-RUN-42");
        servidor.verify();
    }

    @Test
    @DisplayName("La entrega envía a G5 solo los campos que G5 conoce y devuelve su constancia")
    void confirmaEntrega() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/PED-2026-PICKUP01/entrega-pickup"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-de-servicio"))
                .andExpect(content().json("""
                        {"dniRecoge":"72345678","nombreRecoge":"Carlos Mendoza","esTerceroAutorizado":false,"codigoRetiro":null}
                        """, true))
                .andRespond(withSuccess("""
                        {"constanciaId":"CONST-PED-2026-PICKUP01","fechaHoraEntrega":"2026-11-06T10:00:00Z","estado":"ENTREGADO"}
                        """, MediaType.APPLICATION_JSON));

        EntregaPickupResponse constancia = adaptador.confirmarEntrega("PED-2026-PICKUP01",
                new EntregaPickupRequest("72345678", "Carlos Mendoza", false, null, null));

        assertThat(constancia.constanciaId()).isEqualTo("CONST-PED-2026-PICKUP01");
        assertThat(constancia.estado()).isEqualTo("ENTREGADO");
        servidor.verify();
    }

    @Test
    @DisplayName("Un 409 de G5 llega como conflicto con el código de G5")
    void conflictoDeVentas() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/PED-1/entrega-pickup"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"codigo\":\"PEDIDO_NO_LISTO_PARA_RECOJO\",\"mensaje\":\"estado actual: ENTREGADO\"}"));

        assertThatThrownBy(() -> adaptador.confirmarEntrega("PED-1",
                new EntregaPickupRequest("72345678", "Carlos", false, null, null)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("PEDIDO_NO_LISTO_PARA_RECOJO");
    }

    @Test
    @DisplayName("Un 404 de G5 llega como recurso no encontrado")
    void noEncontradoEnVentas() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/PED-X/entrega-pickup"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"codigo\":\"PEDIDO_NO_ENCONTRADO\",\"mensaje\":\"No existe\"}"));

        assertThatThrownBy(() -> adaptador.confirmarEntrega("PED-X",
                new EntregaPickupRequest("72345678", "Carlos", false, null, null)))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Un 5xx de G5 se responde como 503 VENTAS_NO_DISPONIBLE")
    void caidaDeVentas() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/pickup?tiendaId=TIENDA-MIRAFLORES"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adaptador.listarPendientes("TIENDA-MIRAFLORES"))
                .isInstanceOf(ServicioExternoNoDisponibleException.class)
                .extracting("codigo").isEqualTo("VENTAS_NO_DISPONIBLE");
    }

    @Test
    @DisplayName("Si G5 rechaza la credencial se responde 503 VENTAS_CREDENCIAL_RECHAZADA")
    void credencialRechazada() {
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/pickup?tiendaId=TIENDA-MIRAFLORES"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adaptador.listarPendientes("TIENDA-MIRAFLORES"))
                .isInstanceOf(ServicioExternoNoDisponibleException.class)
                .extracting("codigo").isEqualTo("VENTAS_CREDENCIAL_RECHAZADA");
    }
}

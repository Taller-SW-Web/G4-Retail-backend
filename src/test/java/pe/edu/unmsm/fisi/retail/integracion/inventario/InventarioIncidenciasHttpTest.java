package pe.edu.unmsm.fisi.retail.integracion.inventario;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;
import pe.edu.unmsm.fisi.retail.model.OutboxEvento;
import pe.edu.unmsm.fisi.retail.service.outbox.EnvioOutboxException;

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

/** Adaptador real hacia Inventory de G6 (contrato v0.5.2, §5.12 y §2.9). La URL base ya termina en /api/v1. */
@DisplayName("InventarioIncidenciasHttp — reporte de incidencias a Inventory (G6)")
class InventarioIncidenciasHttpTest {

    private static final String BASE = "https://g6-inventario.example/api/v1";
    private static final String PAYLOAD = "{\"incidenciaId\":\"inc-1\",\"canal\":\"RETAIL\"}";

    private MockRestServiceServer servidor;
    private InventarioIncidenciasHttp adaptador;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.obtener()).thenReturn("token-de-servicio");
        adaptador = new InventarioIncidenciasHttp(builder.build(), token);
    }

    @Test
    @DisplayName("Envía el payload con Bearer, Idempotency-Key = incidenciaId y X-Correlation-Id, y lee el id de Inventory")
    void enviaConCabeceras() {
        servidor.expect(requestTo(BASE + "/inventario/incidencias"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-de-servicio"))
                .andExpect(header("Idempotency-Key", "inc-1"))
                .andExpect(header("X-Correlation-Id", "corr-123"))
                .andExpect(content().json(PAYLOAD))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"incidencia_id\":\"INC-INV-77\",\"estado\":\"ABIERTA\",\"blocked\":1}"));

        assertThat(adaptador.enviar(evento())).isEqualTo("INC-INV-77");
        servidor.verify();
    }

    @Test
    @DisplayName("Reemplaza el store_code de la tienda por el location_id de G6 al enviar (§5.12)")
    void traduceLaTiendaAlLocationIdDeG6() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer servidorG6 = MockRestServiceServer.bindTo(builder).build();
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.obtener()).thenReturn("token-de-servicio");
        InventarioIncidenciasHttp conUbicaciones = new InventarioIncidenciasHttp(builder.build(), token,
                InventarioIncidenciasHttp.ubicaciones("TIENDA-MIRAFLORES=aea2f0d8-e8c6-b09c-8e7a-b4a3630bc485"));

        servidorG6.expect(requestTo(BASE + "/inventario/incidencias"))
                .andExpect(content().json("{\"sku\":\"CAM-1\",\"location_id\":\"aea2f0d8-e8c6-b09c-8e7a-b4a3630bc485\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"incidencia_id\":\"INC-INV-1\",\"estado\":\"ABIERTA\"}"));

        OutboxEvento evento = evento();
        evento.setPayload("{\"sku\":\"CAM-1\",\"location_id\":\"TIENDA-MIRAFLORES\"}");
        assertThat(conUbicaciones.enviar(evento)).isEqualTo("INC-INV-1");
        servidorG6.verify();
    }

    @Test
    @DisplayName("Una tienda sin location_id configurado viaja tal cual (G6 la rechaza con 422 y queda FALLIDO)")
    void tiendaSinMapeoViajaTalCual() {
        assertThat(InventarioIncidenciasHttp.ubicaciones(" TIENDA-A = uuid-a , mal-formado ,"))
                .containsExactlyEntriesOf(java.util.Map.of("TIENDA-A", "uuid-a"));
        assertThat(InventarioIncidenciasHttp.ubicaciones("")).isEmpty();
    }

    @Test
    @DisplayName("Un 503 de Inventory es transitorio: el outbox lo reintenta")
    void errorServidorEsTransitorio() {
        servidor.expect(requestTo(BASE + "/inventario/incidencias")).andRespond(withServerError());

        assertThatThrownBy(() -> adaptador.enviar(evento()))
                .isInstanceOfSatisfying(EnvioOutboxException.class, e -> assertThat(e.esDefinitivo()).isFalse());
    }

    @Test
    @DisplayName("Un 403 (scope no registrado) es definitivo: el evento pasa a FALLIDO para revisión")
    void prohibidoEsDefinitivo() {
        servidor.expect(requestTo(BASE + "/inventario/incidencias")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adaptador.enviar(evento()))
                .isInstanceOfSatisfying(EnvioOutboxException.class, e -> assertThat(e.esDefinitivo()).isTrue());
    }

    @Test
    @DisplayName("Sin token de servicio (G7 caído) el envío es transitorio")
    void sinTokenEsTransitorio() {
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.obtener()).thenThrow(new RuntimeException("G7 caído"));
        InventarioIncidenciasHttp sinToken = new InventarioIncidenciasHttp(RestClient.create(BASE), token);

        assertThatThrownBy(() -> sinToken.enviar(evento()))
                .isInstanceOfSatisfying(EnvioOutboxException.class, e -> assertThat(e.esDefinitivo()).isFalse());
    }

    private static OutboxEvento evento() {
        OutboxEvento e = new OutboxEvento();
        e.setAgregadoId("inc-1");
        e.setPayload(PAYLOAD);
        e.setCorrelationId("corr-123");
        return e;
    }
}

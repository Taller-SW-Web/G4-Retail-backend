package pe.edu.unmsm.fisi.retail.integracion.seguridad;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("TokenServicioG7 — token client_credentials de Seguridad (G7)")
class TokenServicioG7Test {

    private static final String URL = "https://g7-auth-service.onrender.com/api/v1/auth/token";

    private final AtomicReference<Instant> ahora = new AtomicReference<>(Instant.parse("2026-11-07T10:00:00Z"));
    private MockRestServiceServer servidor;
    private RestClient restClient;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        servidor = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    @Test
    @DisplayName("Sin secreto configurado no se considera listo")
    void sinSecretoNoEstaConfigurado() {
        assertThat(nuevo("").configurado()).isFalse();
        assertThat(nuevo("   ").configurado()).isFalse();
        assertThat(nuevo("secreto").configurado()).isTrue();
    }

    @Test
    @DisplayName("Pide el token una vez y lo reutiliza mientras está vigente")
    void reutilizaTokenVigente() {
        servidor.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "client_credentials", "client_id", "modulo-retail", "audience", "api-ventas")))
                .andRespond(withSuccess("{\"accessToken\":\"tok-1\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}",
                        MediaType.APPLICATION_JSON));

        TokenServicioG7 token = nuevo("secreto");
        assertThat(token.obtener()).isEqualTo("tok-1");
        ahora.set(ahora.get().plusSeconds(1800));
        assertThat(token.obtener()).isEqualTo("tok-1");
        servidor.verify();
    }

    @Test
    @DisplayName("Cuando el token está por vencer pide uno nuevo")
    void renuevaTokenPorVencer() {
        servidor.expect(requestTo(URL))
                .andRespond(withSuccess("{\"accessToken\":\"tok-1\",\"expiresIn\":3600}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(URL))
                .andRespond(withSuccess("{\"accessToken\":\"tok-2\",\"expiresIn\":3600}", MediaType.APPLICATION_JSON));

        TokenServicioG7 token = nuevo("secreto");
        assertThat(token.obtener()).isEqualTo("tok-1");
        ahora.set(ahora.get().plusSeconds(3550));
        assertThat(token.obtener()).isEqualTo("tok-2");
        servidor.verify();
    }

    @Test
    @DisplayName("Si G7 rechaza las credenciales responde 503 SEGURIDAD_NO_DISPONIBLE")
    void credencialesRechazadas() {
        servidor.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> nuevo("secreto-malo").obtener())
                .isInstanceOf(ServicioExternoNoDisponibleException.class)
                .extracting("codigo").isEqualTo("SEGURIDAD_NO_DISPONIBLE");
    }

    private TokenServicioG7 nuevo(String secreto) {
        Clock reloj = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zona) { return this; }
            @Override public Instant instant() { return ahora.get(); }
        };
        return new TokenServicioG7(restClient, "modulo-retail", secreto, "api-ventas", reloj);
    }
}

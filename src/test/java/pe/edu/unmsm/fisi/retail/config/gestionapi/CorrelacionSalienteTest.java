package pe.edu.unmsm.fisi.retail.config.gestionapi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Propagación del {@code X-Correlation-Id} a los módulos que consume Retail (contrato v0.5, §2.8). */
@DisplayName("CorrelacionSaliente — propagación a G5, G6 y G7")
class CorrelacionSalienteTest {

    private static final String BASE = "https://g5-api-gateway.onrender.com";

    @AfterEach
    void limpiar() {
        MDC.clear();
    }

    @Test
    @DisplayName("La llamada saliente lleva el X-Correlation-Id de la petición en curso")
    void propaga() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE).requestInterceptor(CorrelacionSaliente.INSTANCIA);
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        servidor.expect(requestTo(BASE + "/api/v1/pedidos/PED-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Correlation-Id", "corr-abc"))
                .andRespond(withSuccess());

        MDC.put(Correlacion.MDC_CLAVE, "corr-abc");
        builder.build().get().uri("/api/v1/pedidos/PED-1").retrieve().toBodilessEntity();

        servidor.verify();
    }

    @Test
    @DisplayName("Fuera de una petición HTTP (por ejemplo, un job) no inventa la cabecera")
    void sinPeticionNoAgrega() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE).requestInterceptor(CorrelacionSaliente.INSTANCIA);
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        servidor.expect(requestTo(BASE + "/x"))
                .andExpect(headerDoesNotExist("X-Correlation-Id"))
                .andRespond(withSuccess());

        builder.build().get().uri("/x").retrieve().toBodilessEntity();

        servidor.verify();
    }
}

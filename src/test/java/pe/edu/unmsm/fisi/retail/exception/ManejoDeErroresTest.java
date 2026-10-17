package pe.edu.unmsm.fisi.retail.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el formato canonico de errores del contrato v0.4 (seccion 2.5, decision D-01).
 *
 * <p>Se levanta un servidor real porque el caso que interesa, pedir una ruta que no existe, lo
 * resuelve la infraestructura web antes de llegar a cualquier controlador.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ManejoDeErroresTest {

    @Autowired
    private TestRestTemplate cliente;

    @Test
    @DisplayName("Una ruta inexistente responde 404, no 500")
    void rutaInexistenteDevuelve404() {
        ResponseEntity<String> respuesta = cliente.getForEntity("/api/v1/retail/ruta-que-no-existe", String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("El error viaja en formato problem+json con code, correlationId y timestamp")
    void elErrorUsaElFormatoCanonico() {
        ResponseEntity<String> respuesta = cliente.getForEntity("/api/v1/retail/ruta-que-no-existe", String.class);

        assertThat(respuesta.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(respuesta.getBody())
                .contains("\"code\":\"RUTA_NO_ENCONTRADA\"")
                .contains("\"status\":404")
                .contains("\"correlationId\"")
                .contains("\"timestamp\"")
                .contains("\"instance\":\"/api/v1/retail/ruta-que-no-existe\"");
    }

    @Test
    @DisplayName("Reutiliza el X-Correlation-Id que envia el cliente")
    void reutilizaElIdentificadorDeCorrelacion() {
        String correlacion = "11111111-2222-3333-4444-555555555555";

        ResponseEntity<String> respuesta = cliente.exchange(
                org.springframework.http.RequestEntity
                        .get("/api/v1/retail/ruta-que-no-existe")
                        .header("X-Correlation-Id", correlacion)
                        .build(),
                String.class);

        assertThat(respuesta.getBody()).contains(correlacion);
    }
}

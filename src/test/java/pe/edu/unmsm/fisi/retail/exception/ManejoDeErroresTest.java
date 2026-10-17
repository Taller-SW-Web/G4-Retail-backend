package pe.edu.unmsm.fisi.retail.exception;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el formato canonico de errores del contrato v0.4 (seccion 2.5, decision D-01).
 *
 * <p>Se levanta un servidor real porque el caso que interesa, pedir una ruta que no existe, lo
 * resuelve la infraestructura web antes de llegar a cualquier controlador.</p>
 *
 * <p>Las peticiones viajan <strong>con token</strong>: la cadena de seguridad se ejecuta antes del
 * enrutamiento, asi que una peticion anonima a una ruta inexistente se responde {@code 401} y nunca
 * llega al manejador del {@code 404}. Para probar la ruta inexistente hay que pasar primero la
 * autenticacion, que es tambien lo que le ocurre a la SPA: sus llamadas siempre llevan el Bearer.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Desde F2 el contexto necesita un ProductosPort; el perfil "mock" provee el doble de G6.
@ActiveProfiles({"local", "mock"})
class ManejoDeErroresTest {

    private static final String RUTA_INEXISTENTE = "/api/v1/retail/ruta-que-no-existe";
    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";

    @Autowired
    private TestRestTemplate cliente;

    /** La misma clave compartida con la que el frontend firma el token simulado de la Semana 8. */
    @Value("${retail.seguridad.mock.clave}")
    private String claveMock;

    @Test
    @DisplayName("Una ruta inexistente responde 404, no 500")
    void rutaInexistenteDevuelve404() {
        ResponseEntity<String> respuesta = cliente.exchange(peticion().build(), String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("El error viaja en formato problem+json con code, correlationId y timestamp")
    void elErrorUsaElFormatoCanonico() {
        ResponseEntity<String> respuesta = cliente.exchange(peticion().build(), String.class);

        // Compatible, no identico: el servidor agrega ";charset=UTF-8" al tipo de contenido.
        assertThat(respuesta.getHeaders().getContentType())
                .isNotNull()
                .matches(tipo -> tipo.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                        "compatible con application/problem+json");
        assertThat(respuesta.getBody())
                .contains("\"code\":\"RUTA_NO_ENCONTRADA\"")
                .contains("\"status\":404")
                .contains("\"correlationId\"")
                .contains("\"timestamp\"")
                .contains("\"instance\":\"" + RUTA_INEXISTENTE + "\"");
    }

    @Test
    @DisplayName("Reutiliza el X-Correlation-Id que envia el cliente")
    void reutilizaElIdentificadorDeCorrelacion() {
        String correlacion = "11111111-2222-3333-4444-555555555555";

        ResponseEntity<String> respuesta = cliente.exchange(
                peticion().header("X-Correlation-Id", correlacion).build(), String.class);

        assertThat(respuesta.getBody()).contains(correlacion);
    }

    @Test
    @DisplayName("Sin token la ruta inexistente se corta en el 401, antes de saber si existe")
    void sinTokenSeCortaEn401() {
        ResponseEntity<String> respuesta = cliente.getForEntity(RUTA_INEXISTENTE, String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(respuesta.getBody()).contains("\"code\":\"TOKEN_INVALIDO\"");
    }

    /** Peticion autenticada a la ruta inexistente, que es el caso que se quiere ejercitar. */
    private RequestEntity.HeadersBuilder<?> peticion() {
        return RequestEntity.get(URI.create(RUTA_INEXISTENTE)).header("Authorization", "Bearer " + token());
    }

    /**
     * Firma un token valido con la clave de desarrollo, igual que hace el frontend.
     *
     * <p>No se puede usar el post-procesador {@code jwt()} de Spring Security: aqui hay un servidor
     * real y la peticion sale por HTTP, asi que el token tiene que ser uno de verdad.</p>
     */
    private String token() {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(VENDEDOR)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(claveMock.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el token de prueba", e);
        }
    }
}

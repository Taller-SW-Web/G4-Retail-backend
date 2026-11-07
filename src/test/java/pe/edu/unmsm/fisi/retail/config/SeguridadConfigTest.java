package pe.edu.unmsm.fisi.retail.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeguridadConfigTest {

    private static final String CLAVE_MOCK = "clave-de-desarrollo-g4-retail-solo-para-la-semana-8";
    private static final String USUARIO_ID = "bbbbbbbb-0000-4000-8000-000000000001";

    private static RSAKey claveG7;
    private static RSAKey claveAjena;
    private static HttpServer servidorJwks;
    private JwtDecoder decodificador;

    @BeforeAll
    static void publicarJwksLocal() throws IOException, JOSEException {
        claveG7 = new RSAKeyGenerator(2048).keyID("g7-pruebas").generate();
        claveAjena = new RSAKeyGenerator(2048).generate();
        byte[] jwks = new JWKSet(claveG7.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);

        servidorJwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidorJwks.createContext("/jwks", intercambio -> {
            intercambio.getResponseHeaders().set("Content-Type", "application/json");
            intercambio.sendResponseHeaders(200, jwks.length);
            try (var respuesta = intercambio.getResponseBody()) {
                respuesta.write(jwks);
            }
        });
        servidorJwks.start();
    }

    @AfterAll
    static void cerrarJwksLocal() {
        if (servidorJwks != null) {
            servidorJwks.stop(0);
        }
    }

    @BeforeEach
    void configurarDecodificador() {
        String jwksUri = "http://127.0.0.1:" + servidorJwks.getAddress().getPort() + "/jwks";
        decodificador = new SeguridadConfig(CLAVE_MOCK, jwksUri, new ObjectMapper()).decodificadorDeToken();
    }

    @Test
    @DisplayName("Acepta un token HMAC de prueba firmado con la clave de desarrollo")
    void decodificaTokenHmac() throws JOSEException {
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims(expiracionValida()));
        token.sign(new MACSigner(CLAVE_MOCK.getBytes(StandardCharsets.UTF_8)));

        assertThat(decodificador.decode(token.serialize()).getSubject()).isEqualTo(USUARIO_ID);
    }

    @Test
    @DisplayName("Rechaza un token con formato inválido")
    void rechazaTokenInvalido() {
        assertThatThrownBy(() -> decodificador.decode("token.totalmente.invalido"))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    @DisplayName("Acepta un token RS256 vigente firmado con la clave publicada en el JWKS")
    void decodificaTokenRs256G7() throws JOSEException {
        Jwt jwt = decodificador.decode(tokenRs256(claveG7, expiracionValida()));

        assertThat(jwt.getSubject()).isEqualTo(USUARIO_ID);
    }

    @Test
    @DisplayName("Rechaza un token RS256 firmado con una clave ajena al JWKS")
    void rechazaFirmaRs256Invalida() throws JOSEException {
        String token = tokenRs256(claveAjena, expiracionValida());

        assertThatThrownBy(() -> decodificador.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    @DisplayName("Rechaza un token RS256 vencido aunque su firma sea válida")
    void rechazaTokenRs256Vencido() throws JOSEException {
        String token = tokenRs256(claveG7, Instant.now().minus(5, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> decodificador.decode(token)).isInstanceOf(BadJwtException.class);
    }

    private static String tokenRs256(RSAKey clave, Instant expiracion) throws JOSEException {
        JWSHeader cabecera = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(claveG7.getKeyID()).build();
        SignedJWT token = new SignedJWT(cabecera, claims(expiracion));
        token.sign(new RSASSASigner(clave));
        return token.serialize();
    }

    private static JWTClaimsSet claims(Instant expiracion) {
        return new JWTClaimsSet.Builder()
                .subject(USUARIO_ID)
                .expirationTime(Date.from(expiracion))
                .build();
    }

    private static Instant expiracionValida() {
        return Instant.now().plus(5, ChronoUnit.MINUTES);
    }
}

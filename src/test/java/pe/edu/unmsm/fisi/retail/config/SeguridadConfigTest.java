package pe.edu.unmsm.fisi.retail.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias para {@link SeguridadConfig} y la resolución bimodal de tokens (HMAC vs RS256 JWKS).
 */
class SeguridadConfigTest {

    private static final String CLAVE_MOCK = "clave-de-desarrollo-g4-retail-solo-para-la-semana-8";
    private static final String JWKS_URI = "https://g7-auth-service.onrender.com/api/v1/auth/.well-known/jwks.json";

    private final SeguridadConfig config = new SeguridadConfig(CLAVE_MOCK, JWKS_URI, new ObjectMapper());

    @Test
    @DisplayName("Decodifica correctamente un token HMAC de prueba firmado con la clave de desarrollo")
    void decodificaTokenHmac() {
        // Token simulado del vendedor 1 (definido en el mock de la semana 8)
        String tokenVendedor = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                + "eyJzdWIiOiJiYmJiYmJiYi0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJ0aXBvIjoiYWNjZXNvIiwibm9tYnJlIjoiTHVjaWEgUmFtaXJleiBWZWdhIiwiaXNzIjoiaHR0cHM6Ly9zZWd1cmlkYWQuZzQvbW9jayIsImlhdCI6MTc5MjE4NTE2NCwiZXhwIjoxNzk0Nzc3MTY0fQ."
                + "toWybfZYm6fZzhhtxKOwKMI1wP-w4kuEyLuxnSp8Fq4";

        JwtDecoder decoder = config.decodificadorDeToken();
        Jwt jwt = decoder.decode(tokenVendedor);

        assertThat(jwt.getSubject()).isEqualTo("bbbbbbbb-0000-0000-0000-000000000001");
        assertThat(jwt.getClaimAsString("nombre")).isEqualTo("Lucia Ramirez Vega");
    }

    @Test
    @DisplayName("Rechaza un token manipulado o con formato inválido lanzando BadJwtException")
    void rechazaTokenInvalido() {
        JwtDecoder decoder = config.decodificadorDeToken();

        assertThatThrownBy(() -> decoder.decode("token.totalmente.invalido"))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "TEST_INTEGRACION_G7", matches = "true")
    @DisplayName("Decodifica un token real RS256 emitido por Seguridad (G7) validando su firma con JWKS")
    void decodificaTokenRealRs256G7() {
        String tokenG7 = "eyJraWQiOiIxMlZuU2xnZXNtMG9rVlJNQWRsdFh5Ui13SkJ4c1JJNDRRaU9jbm0xWGVRIiwidHlwIjoiSldUIiwiYWxnIjoiUlMyNTYifQ."
                + "eyJzdWIiOiJiOWUyMjJhNS1jZjg2LTRjOTUtOTYxMS04OTE4ODYxNjA4MjEiLCJ0aXBvIjoiYWNjZXNvIiwicm9sZXMiOlsiVkVOREVET1IiXSwiaXNzIjoiaHR0cHM6Ly9nNy1hdXRoLXNlcnZpY2Uub25yZW5kZXIuY29tL2FwaS92MS9hdXRoIiwiZXhwIjoxNzkyMjIzNzYwLCJwZXJtaXNvcyI6WyJjbGllbnRlLmJ1c2NhciIsImNsaWVudGUucmVnaXN0cmFyIl0sImlhdCI6MTc5MjIyMjg2MCwianRpIjoiNGM4MjM2MDEtMDQzNC00OTk3LTk0ZWEtNGJhZDU4OWU5N2NkIiwiZW1haWwiOiJtaWd1ZWwuc29saXNAdW5tc20uZWR1LnBlIn0."
                + "FPBrdSgUv3g6LBmuubojLR-Vk66W13erZos1DJcP0Qyrf2-HCM-68748aZPlIugubJa5NaXXlS3cs_JD4IKyCUNePy_y-56raIUdSjjTrDyFTXOBfT_ZrGwFOpFVil058avMD2BSCqjdpkr67IzOVLvnB_zNhx-28kIiKX1bW-cFW_iM9o3UNyC0zA_svzSx7V-NJqPwi2cvdZ3pqUS7cTz9obYYvn0uXEaRGFx_MrZdRc29xC_YTpSxgqBxIxeQr5GYZs0odl3DfeIJ7FCyvezxLSeUvHXaODtTfp3NUtqII8qajOsNowFbA33pBL1HqGcivv76I0VCNuB3wd6nGQ0ACN88GbxJ9PrWO_McbgqIEkt0EYPzVeiNN8Zpg5E7wzjGNrorwjqKNYDy9gYoEmeThL9LK3so1QH44rUHgR-qMEZVAeLmjRzgj_YIYHG49J0UHQ9owv70VXbTRJ6ExVIKfrmRepqVr5ugGNn2WoIZuhTUivX7n7_T90IIIW99";

        JwtDecoder decoder = config.decodificadorDeToken();
        Jwt jwt = decoder.decode(tokenG7);

        assertThat(jwt.getSubject()).isEqualTo("b9e222a5-cf86-4c95-9611-891886160821");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("miguel.solis@unmsm.edu.pe");
        assertThat(jwt.getClaimAsStringList("roles")).contains("VENDEDOR");
    }
}

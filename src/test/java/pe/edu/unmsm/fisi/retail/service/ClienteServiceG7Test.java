package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.dto.ClienteAltaRapidaRequest;
import pe.edu.unmsm.fisi.retail.dto.ClienteResponse;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.DatoInvalidoException;
import pe.edu.unmsm.fisi.retail.exception.DemasiadasSolicitudesException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.ClientesG7Http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** F3 contra Seguridad (G7), con los payloads de su OpenAPI (contrato v0.5, §4.1). */
@DisplayName("ClienteServiceG7 — clientes en /usuarios/* de Seguridad")
class ClienteServiceG7Test {

    private static final String BASE = "https://g7.example";

    private MockRestServiceServer servidor;
    private ClienteServiceG7 servicio;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        servicio = new ClienteServiceG7(new ClientesG7Http(builder.build()));
        Jwt jwt = Jwt.withTokenValue("jwt-del-vendedor").header("alg", "RS256").subject("vendedor-1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Busca por documento con el token del vendedor y devuelve el documento enmascarado")
    void buscaConTokenDelVendedor() {
        servidor.expect(requestTo(BASE + "/api/v1/usuarios/busqueda-documento"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer jwt-del-vendedor"))
                .andExpect(content().json("{\"tipoDocumento\":\"DNI\",\"numeroDocumento\":\"45781234\"}"))
                .andRespond(withSuccess("{\"id\":\"8d4e0c3a-91b2-4f1e-a0c7-5e2b7f6d9a10\",\"nombreCompleto\":\"María Quispe Rojas\","
                        + "\"documentoEnmascarado\":\"*****234\",\"estado\":\"ACTIVO\"}", MediaType.APPLICATION_JSON));

        ClienteResponse cliente = servicio.buscarPorDocumento("DNI", "45781234");

        assertThat(cliente.clienteId()).isEqualTo("8d4e0c3a-91b2-4f1e-a0c7-5e2b7f6d9a10");
        assertThat(cliente.numeroDocumento()).isEqualTo("*****234");
        assertThat(cliente.nombreCompleto()).isEqualTo("María Quispe Rojas");
    }

    @Test
    @DisplayName("RF-09: un DNI mal formado se rechaza en Retail sin consultar a G7")
    void documentoInvalidoNoLlamaAG7() {
        assertThatThrownBy(() -> servicio.buscarPorDocumento("DNI", "123"))
                .isInstanceOf(DatoInvalidoException.class);
        servidor.verify();
    }

    @Test
    @DisplayName("Un 404 de G7 es CLIENTE_NO_ENCONTRADO (el vendedor pasa al alta rápida)")
    void noEncontrado() {
        servidor.expect(requestTo(BASE + "/api/v1/usuarios/busqueda-documento")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> servicio.buscarPorDocumento("DNI", "45781234"))
                .isInstanceOfSatisfying(RecursoNoEncontradoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo("CLIENTE_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("El 429 de G7 (60 búsquedas por hora) llega como DEMASIADAS_SOLICITUDES con su Retry-After")
    void limiteDeG7() {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.add(HttpHeaders.RETRY_AFTER, "1800");
        servidor.expect(requestTo(BASE + "/api/v1/usuarios/busqueda-documento"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(cabeceras));

        assertThatThrownBy(() -> servicio.buscarPorDocumento("DNI", "45781234"))
                .isInstanceOfSatisfying(DemasiadasSolicitudesException.class,
                        e -> assertThat(e.getReintentarEnSegundos()).isEqualTo(1800));
    }

    @Test
    @DisplayName("El alta envía correo, nombres, documento y el celular en formato +51")
    void altaRapida() {
        servidor.expect(requestTo(BASE + "/api/v1/usuarios/clientes"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer jwt-del-vendedor"))
                .andExpect(content().json("{\"correo\":\"rosa@correo.pe\",\"nombres\":\"Rosa\",\"apellidos\":\"Quispe\","
                        + "\"celular\":\"+51987654321\",\"tipoDocumento\":\"DNI\",\"numeroDocumento\":\"40123987\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"c-1\",\"estado\":\"PENDIENTE_VERIFICACION\"}"));

        ClienteResponse cliente = servicio.registrarAltaRapida(new ClienteAltaRapidaRequest("RETAIL", "DNI", "40123987",
                "Rosa", "Quispe", "rosa@correo.pe", "987 654 321"));

        assertThat(cliente.clienteId()).isEqualTo("c-1");
        assertThat(cliente.nombreCompleto()).isEqualTo("Rosa Quispe");
    }

    @Test
    @DisplayName("Sin correo el alta se rechaza: G7 lo exige para enviar el enlace de activación")
    void altaSinCorreo() {
        assertThatThrownBy(() -> servicio.registrarAltaRapida(new ClienteAltaRapidaRequest("RETAIL", "DNI", "40123987",
                "Rosa", "Quispe", null, null)))
                .isInstanceOfSatisfying(DatoInvalidoException.class, e -> assertThat(e.getCampo()).isEqualTo("email"));
    }

    @Test
    @DisplayName("El 409 DOCUMENTO_NO_DISPONIBLE de G7 es CLIENTE_YA_REGISTRADO")
    void documentoYaRegistrado() {
        servidor.expect(requestTo(BASE + "/api/v1/usuarios/clientes"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":409,\"code\":\"DOCUMENTO_NO_DISPONIBLE\"}"));

        assertThatThrownBy(() -> servicio.registrarAltaRapida(new ClienteAltaRapidaRequest("RETAIL", "DNI", "40123987",
                "Rosa", "Quispe", "rosa@correo.pe", null)))
                .isInstanceOfSatisfying(ConflictoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo("CLIENTE_YA_REGISTRADO"));
    }

    @Test
    @DisplayName("El celular solo viaja si se puede llevar al formato +51 y 9 dígitos")
    void normalizaCelular() {
        assertThat(ClienteServiceG7.celular("+51 987-654-321")).isEqualTo("+51987654321");
        assertThat(ClienteServiceG7.celular("987654321")).isEqualTo("+51987654321");
        assertThat(ClienteServiceG7.celular("12345")).isNull();
    }
}

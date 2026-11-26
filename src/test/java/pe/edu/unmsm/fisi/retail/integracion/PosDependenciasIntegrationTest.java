package pe.edu.unmsm.fisi.retail.integracion;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato §2.4, §5.2, §5.4 y §5.6: API + servicios + BD + adaptadores HTTP reales.
 * G5/G7 son servidores de prueba por TCP; G6 usa el fixture existente. No prueba la nube.
 */
@SpringBootTest(properties = {
        "retail.integracion.ventas.habilitado=false",
        "retail.integracion.ventas.pedidos.modo=g5",
        "retail.integracion.ventas.pickup.modo=memoria",
        "retail.integracion.g7.client-secret=secreto-ficticio-s14",
        "retail.integracion.ventas.pedidos.timeout-ms=1000",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosDependenciasIntegrationTest {
    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final AtomicInteger estadoG5 = new AtomicInteger(200);
    private static final AtomicInteger llamadasG5 = new AtomicInteger();
    private static final AtomicReference<String> bearerG5 = new AtomicReference<>();
    private static final AtomicReference<String> formularioG7 = new AtomicReference<>();
    private static final HttpServer servidor = iniciarServidor();

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void dependencias(DynamicPropertyRegistry properties) {
        properties.add("retail.integracion.ventas.url", PosDependenciasIntegrationTest::baseUrl);
        properties.add("retail.integracion.g7.token-url", () -> baseUrl() + "/token");
        String url = System.getenv("S14_DB_URL");
        if (url != null && !url.isBlank()) {
            // create-drop solo se autoriza para la BD efímera nombrada explícitamente.
            if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/retail_s14")) {
                throw new IllegalArgumentException("S14_DB_URL debe apuntar a retail_s14 local y descartable");
            }
            properties.add("spring.datasource.url", () -> url);
            properties.add("spring.datasource.username", () -> System.getenv("S14_DB_USER"));
            properties.add("spring.datasource.password", () -> System.getenv("S14_DB_PASSWORD"));
        } else {
            properties.add("spring.datasource.url", () -> "jdbc:h2:mem:retail_s14;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        }
    }

    @BeforeEach
    void reiniciarEscenario() {
        estadoG5.set(200);
        llamadasG5.set(0);
        bearerG5.set(null);
    }

    @AfterAll
    static void cerrarServidor() { servidor.stop(0); }

    @Test
    void consultaPedidoConPerfilPersistidoYTokenDeServicio() throws Exception {
        mvc.perform(get("/api/v1/retail/pedidos/PED-S14").with(jwt().jwt(t -> t.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidoId").value("PED-S14"))
                .andExpect(jsonPath("$.estadoComercial").value("PAGADO"));
        assertThat(llamadasG5.get()).isEqualTo(1);
        assertThat(bearerG5.get()).isEqualTo("Bearer token-ficticio-s14");
        assertThat(formularioG7.get()).contains("grant_type=client_credentials", "client_secret=secreto-ficticio-s14");
        try (var conexion = dataSource.getConnection()) {
            assertThat(conexion.getMetaData().getDatabaseProductName())
                    .isEqualTo(System.getenv("S14_DB_URL") == null || System.getenv("S14_DB_URL").isBlank()
                            ? "H2" : "PostgreSQL");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 503})
    void traduceFallosDeVentasSinInventarPedidos(int estado) throws Exception {
        estadoG5.set(estado);
        String codigo = switch (estado) {
            case 404 -> "PEDIDO_NO_ENCONTRADO";
            case 403 -> "VENTAS_CREDENCIAL_RECHAZADA";
            default -> "VENTAS_NO_DISPONIBLE";
        };
        mvc.perform(get("/api/v1/retail/pedidos/PED-S14").with(jwt().jwt(t -> t.subject(VENDEDOR))))
                .andExpect(status().is(estado == 404 ? 404 : 503))
                .andExpect(jsonPath("$.code").value(codigo));
        assertThat(llamadasG5.get()).isEqualTo(1);
    }

    @Test
    void sinAutenticacionNoConsultaDependencias() throws Exception {
        mvc.perform(get("/api/v1/retail/pedidos/PED-S14")).andExpect(status().isUnauthorized());
        assertThat(llamadasG5.get()).isZero();
    }

    @Test
    void usuarioSinPerfilEnBaseDeDatosNoConsultaVentas() throws Exception {
        mvc.perform(get("/api/v1/retail/pedidos/PED-S14")
                        .with(jwt().jwt(t -> t.subject("00000000-0000-0000-0000-000000000099"))))
                .andExpect(status().isForbidden());
        assertThat(llamadasG5.get()).isZero();
    }

    @Test
    void g7SaturadoNoEntregaCredencialFicticia() {
        var token = new TokenServicioG7(baseUrl() + "/token-saturado", "retail-prueba", "ficticio", "api-ventas", 1000);
        assertThatThrownBy(token::obtener).isInstanceOf(ServicioExternoNoDisponibleException.class)
                .extracting("codigo").isEqualTo("SEGURIDAD_NO_DISPONIBLE");
    }

    @Test
    void escaneoResuelveVarianteYPrecioDelFixtureG6() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo/codigo-barras/7750101000012").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variante.sku").value("ZAPVEL-38-NEG"))
                .andExpect(jsonPath("$.precio").value(249.00));
    }

    @Test
    void codigoInexistenteNoSeConvierteEnProducto() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo/codigo-barras/0000000000000").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CODIGO_BARRAS_NO_ENCONTRADO"));
    }

    @Test
    void calculoHttpRespetaDesgloseDeIgv() throws Exception {
        String respuesta = mvc.perform(post("/api/v1/retail/carritos/calcular").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"sku":"ZAPVEL-38-NEG","cantidad":2}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moneda").value("PEN"))
                .andReturn().getResponse().getContentAsString();
        var json = JsonPath.parse(respuesta);
        BigDecimal total = new BigDecimal(json.read("$.totalPagar").toString());
        BigDecimal subtotal = new BigDecimal(json.read("$.subtotalBruto").toString());
        BigDecimal descuento = new BigDecimal(json.read("$.descuentoTotal").toString());
        BigDecimal base = new BigDecimal(json.read("$.baseImponible").toString());
        BigDecimal igv = new BigDecimal(json.read("$.montoIgv").toString());
        assertThat(total).isPositive().isEqualByComparingTo(subtotal.subtract(descuento));
        assertThat(base).isEqualByComparingTo(total.divide(new BigDecimal("1.18"), 2, RoundingMode.HALF_UP));
        assertThat(base.add(igv)).isEqualByComparingTo(total);
    }

    private static String baseUrl() { return "http://127.0.0.1:" + servidor.getAddress().getPort(); }

    private static HttpServer iniciarServidor() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/token", exchange -> {
                formularioG7.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                responder(exchange, 200, "{\"accessToken\":\"token-ficticio-s14\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}");
            });
            http.createContext("/token-saturado", exchange -> responder(exchange, 429, "{}"));
            http.createContext("/api/v1/pedidos/PED-S14", exchange -> {
                llamadasG5.incrementAndGet();
                bearerG5.set(exchange.getRequestHeaders().getFirst("Authorization"));
                responder(exchange, estadoG5.get(), """
                        {"pedidoId":"PED-S14","canal":"RETAIL","tipo":"VENTA","estado":"PAGADO",
                         "pago":{"total":118.00,"moneda":"PEN","metodoPago":"EFECTIVO","estado":"CONFIRMADO"},
                         "items":[],"historialEstados":[]}
                        """);
            });
            http.start();
            return http;
        } catch (IOException e) { throw new IllegalStateException("No se pudo iniciar el doble HTTP local", e); }
    }

    private static void responder(HttpExchange exchange, int estado, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(estado, bytes.length);
        try (var salida = exchange.getResponseBody()) { salida.write(bytes); }
        exchange.close();
    }
}

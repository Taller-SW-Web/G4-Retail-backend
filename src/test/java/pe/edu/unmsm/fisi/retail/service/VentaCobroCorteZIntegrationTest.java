package pe.edu.unmsm.fisi.retail.service;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.model.CobroTurno;
import pe.edu.unmsm.fisi.retail.repository.CobroTurnoRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo de punta a punta, con repositorios reales: venta en mostrador → cobro en el turno → Corte Z.
 *
 * <p>No hay stubs de repositorio: el turno se abre por la API, la venta pasa por
 * {@code VentaController} con el {@code sub} del JWT y el efectivo del Reporte Z sale de la suma que
 * hace {@code CobroTurnoRepository} sobre lo que realmente se guardó. La integración con G5 se apaga
 * para que el pedido use el correlativo local. Los datos de personal vienen de
 * {@code datos-local.sql}; la clase es transaccional, así que cada prueba deshace lo que creó.</p>
 */
@SpringBootTest(properties = "retail.integracion.ventas.habilitado=false")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@Transactional
@DisplayName("Flujo integrado: venta en mostrador -> cobro en el turno -> Corte Z")
class VentaCobroCorteZIntegrationTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String TERMINAL = "POS-01";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CobroTurnoRepository cobroTurnoRepository;

    @Test
    @DisplayName("El efectivo cobrado computa en el Corte Z y la tarjeta queda fuera, sin sumas fijadas en la prueba")
    void ventasDelTurnoComputanEnCorteZ() throws Exception {
        String turnoId = abrirTurno();

        registrarVenta(conToken(VENDEDOR), "idemp-efectivo-1", venta(TERMINAL, turnoId,
                "{\"medioPago\":\"EFECTIVO\",\"montoRecibidoEfectivo\":150.00}", "100.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estadoPedido").value("PAGADO"))
                .andExpect(jsonPath("$.pago.vuelto").value(50.00));
        registrarVenta(conToken(VENDEDOR), "idemp-efectivo-2", venta(TERMINAL, turnoId,
                "{\"medioPago\":\"EFECTIVO\",\"montoRecibidoEfectivo\":35.50}", "35.50"))
                .andExpect(status().isCreated());
        registrarVenta(conToken(VENDEDOR), "idemp-tarjeta-1", venta(TERMINAL, turnoId,
                "{\"medioPago\":\"TARJETA_POS\",\"montoTarjeta\":80.00,\"referenciaOperacion\":\"OP-7788\"}", "80.00"))
                .andExpect(status().isCreated());

        CobroTurno cobro = cobroTurnoRepository.findByIdempotencyKey("idemp-efectivo-1").orElseThrow();
        assertThat(cobro.getCajaSesionId()).isEqualTo(UUID.fromString(turnoId));
        assertThat(cobro.getMontoTotal()).isEqualByComparingTo("100.00");
        assertThat(cobro.getComprobanteSerie()).isEqualTo("B001");

        // Saldo inicial 200 + efectivo 135.50 (la tarjeta no entra al cajón) = 335.50
        mvc.perform(post("/api/v1/retail/caja/turnos/" + turnoId + "/cierre")
                        .with(conToken(VENDEDOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montoDeclarado\":335.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoInicial").value(200.00))
                .andExpect(jsonPath("$.ventasEfectivoTotal").value(135.50))
                .andExpect(jsonPath("$.saldoSistema").value(335.50))
                .andExpect(jsonPath("$.diferencia").value(0.00))
                .andExpect(jsonPath("$.estado").value("CERRADA"));
    }

    @Test
    @DisplayName("Reintentar con la misma Idempotency-Key no duplica el cobro en el Corte Z")
    void reintentoNoDuplicaElCobro() throws Exception {
        String turnoId = abrirTurno();
        String cuerpo = venta(TERMINAL, turnoId,
                "{\"medioPago\":\"EFECTIVO\",\"montoRecibidoEfectivo\":100.00}", "100.00");

        registrarVenta(conToken(VENDEDOR), "idemp-reintento", cuerpo).andExpect(status().isCreated());
        registrarVenta(conToken(VENDEDOR), "idemp-reintento", cuerpo).andExpect(status().isCreated());

        mvc.perform(post("/api/v1/retail/caja/turnos/" + turnoId + "/cierre")
                        .with(conToken(VENDEDOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montoDeclarado\":300.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventasEfectivoTotal").value(100.00));
    }

    @Test
    @DisplayName("Negativo: el turno no sustituye al vendedor; un sub sin perfil activo responde 403 y no cobra")
    void turnoNoSustituyeAlVendedor() throws Exception {
        String turnoId = abrirTurno();

        // El sub del token es el propio turnoId: antes esto se saltaba la validación de perfil/tienda
        registrarVenta(conToken(turnoId), "idemp-sin-perfil", venta(TERMINAL, turnoId,
                "{\"medioPago\":\"EFECTIVO\",\"montoRecibidoEfectivo\":100.00}", "100.00"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_USUARIO_SIN_TIENDA"));

        assertThat(cobroTurnoRepository.findByIdempotencyKey("idemp-sin-perfil")).isEmpty();
    }

    @Test
    @DisplayName("Negativo: una venta desde otra terminal responde 422 TERMINAL_NO_COINCIDE y no cobra")
    void terminalDistintaALaDelTurno() throws Exception {
        String turnoId = abrirTurno();

        registrarVenta(conToken(VENDEDOR), "idemp-otra-terminal", venta("POS-99", turnoId,
                "{\"medioPago\":\"EFECTIVO\",\"montoRecibidoEfectivo\":100.00}", "100.00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TERMINAL_NO_COINCIDE"));

        assertThat(cobroTurnoRepository.findByIdempotencyKey("idemp-otra-terminal")).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions registrarVenta(
            RequestPostProcessor token, String idempotencyKey, String cuerpo) throws Exception {
        return mvc.perform(post("/api/v1/retail/ventas")
                .with(token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo));
    }

    /** Cuerpo de una venta de un solo item por {@code precio}, con boleta sin DNI (menor a S/ 700). */
    private static String venta(String terminal, String turnoId, String pago, String precio) {
        return """
                {"terminalPos":"%s","turnoId":"%s",
                 "items":[{"sku":"SKU-POLO","cantidad":1,"precioFinal":%s}],
                 "pago":%s,
                 "comprobante":{"tipo":"BOLETA"},
                 "emitirTicketRegalo":false}
                """.formatted(terminal, turnoId, precio, pago);
    }

    /** Abre el turno de la terminal de prueba con S/ 200 y devuelve su {@code turnoId}. */
    private String abrirTurno() throws Exception {
        String respuesta = mvc.perform(post("/api/v1/retail/caja/turnos")
                        .with(conToken(VENDEDOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalPos\":\"" + TERMINAL + "\",\"saldoInicialEfectivo\":200.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(respuesta, "$.turnoId");
    }

    private static RequestPostProcessor conToken(String sub) {
        return jwt().jwt(token -> token.subject(sub));
    }
}

package pe.edu.unmsm.fisi.retail.service;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.CobroTurnoRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regresiones del contrato Retail §2.3 (idempotencia), §2.4 (perfil/tienda) y F5/F8. */
@SpringBootTest(properties = "retail.integracion.ventas.habilitado=false")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@Transactional
class VentaReintentosIntegrationTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String TERMINAL = "POS-REINTENTOS";

    @Autowired private MockMvc mvc;
    @Autowired private PersonalTiendaRepository personal;
    @Autowired private CobroTurnoRepository cobros;

    @Test
    @DisplayName("§2.4: un reintento sin perfil activo recibe 403 aunque exista una respuesta guardada")
    void reintentoSinPerfilSeRechaza() throws Exception {
        String turnoId = abrirTurno(VENDEDOR);
        String clave = UUID.randomUUID().toString();
        String cuerpo = venta(turnoId);
        vender(VENDEDOR, clave, cuerpo).andExpect(status().isCreated());

        vender(turnoId, clave, cuerpo)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_USUARIO_SIN_TIENDA"));
    }

    @Test
    @DisplayName("§2.4: un vendedor de otra tienda no recupera la venta cambiando el turno del reintento")
    void reintentoDeOtraTiendaSeRechaza() throws Exception {
        String turnoId = abrirTurno(VENDEDOR);
        String clave = UUID.randomUUID().toString();
        vender(VENDEDOR, clave, venta(turnoId)).andExpect(status().isCreated());

        UUID otroVendedor = UUID.randomUUID();
        personal.saveAndFlush(PersonalTienda.builder()
                .usuarioId(otroVendedor).tiendaId("TIENDA-AJENA").codigoVendedor("VEN-AJENO")
                .perfilTienda(PerfilTienda.VENDEDOR).activo(true).build());
        String turnoAjeno = abrirTurno(otroVendedor.toString(), "POS-AJENA");

        vender(otroVendedor.toString(), clave, venta(turnoAjeno))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_TURNO_DE_OTRA_TIENDA"));
    }

    @Test
    @DisplayName("§2.3 y RF-22: el reintento autorizado tras cerrar devuelve la venta original sin cobrar otra vez")
    void reintentoDespuesDelCierreDevuelveOriginal() throws Exception {
        String turnoId = abrirTurno(VENDEDOR);
        String clave = UUID.randomUUID().toString();
        String cuerpo = venta(turnoId);
        String original = vender(VENDEDOR, clave, cuerpo).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID cobroId = cobros.findByIdempotencyKey(clave).orElseThrow().getIdCobro();

        mvc.perform(post("/api/v1/retail/caja/turnos/" + turnoId + "/cierre")
                        .with(jwt().jwt(token -> token.subject(VENDEDOR)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"montoDeclarado\":300.00}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CERRADA"));

        vender(VENDEDOR, clave, cuerpo).andExpect(status().isCreated()).andExpect(content().json(original));
        assertThat(cobros.findByIdempotencyKey(clave).orElseThrow().getIdCobro()).isEqualTo(cobroId);
    }

    @ParameterizedTest(name = "§2.3: reutilizar clave cambiando {0} responde 409")
    @ValueSource(strings = {"importe", "medioPago", "recibido", "cliente", "documento", "sku",
            "cantidad", "terminal", "turno", "cupon", "evaluacion", "regalo"})
    void mismaClaveConOtroCuerpoSeRechaza(String campo) throws Exception {
        String turnoId = abrirTurno(VENDEDOR);
        String clave = UUID.randomUUID().toString();
        String cuerpo = venta(turnoId);
        vender(VENDEDOR, clave, cuerpo).andExpect(status().isCreated());
        UUID cobroId = cobros.findByIdempotencyKey(clave).orElseThrow().getIdCobro();

        String otroCuerpo = switch (campo) {
            case "importe" -> cuerpo.replace("100.00", "200.00");
            case "medioPago" -> cuerpo.replace("\"medioPago\":\"EFECTIVO\",\"montoRecibidoEfectivo\":100.00",
                    "\"medioPago\":\"TARJETA_POS\",\"montoTarjeta\":100.00,\"referenciaOperacion\":\"OP-7788\"");
            case "recibido" -> cuerpo.replace("\"montoRecibidoEfectivo\":100.00", "\"montoRecibidoEfectivo\":150.00");
            case "cliente" -> cuerpo.replace("\"items\":", "\"clienteId\":\"cliente-otro\",\"items\":");
            case "documento" -> cuerpo.replace("\"tipo\":\"BOLETA\"", "\"tipo\":\"BOLETA\",\"numeroDocumento\":\"12345678\"");
            case "sku" -> cuerpo.replace("SKU-POLO", "SKU-CASACA");
            case "cantidad" -> cuerpo.replace("\"cantidad\":1", "\"cantidad\":2");
            case "terminal" -> cuerpo.replace(TERMINAL, "POS-OTRA");
            case "turno" -> cuerpo.replace(turnoId, UUID.randomUUID().toString());
            case "cupon" -> cuerpo.replace("\"items\":", "\"cupon\":\"OTRO\",\"items\":");
            case "evaluacion" -> cuerpo.replace("\"items\":", "\"evaluacionId\":\"EVAL-OTRA\",\"items\":");
            case "regalo" -> cuerpo.replace("\"emitirTicketRegalo\":false", "\"emitirTicketRegalo\":true");
            default -> throw new IllegalArgumentException("Campo de prueba desconocido: " + campo);
        };

        vender(VENDEDOR, clave, otroCuerpo)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(cobros.findByIdempotencyKey(clave).orElseThrow().getIdCobro()).isEqualTo(cobroId);
        assertThat(cobros.findByIdempotencyKey(clave).orElseThrow().getMontoTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("§2.3: importes numéricamente iguales devuelven el resultado original sin duplicar cobros")
    void mismaVentaConDistintaEscalaDecimalDevuelveOriginal() throws Exception {
        String turnoId = abrirTurno(VENDEDOR);
        String clave = UUID.randomUUID().toString();
        String cuerpo = venta(turnoId);
        String original = vender(VENDEDOR, clave, cuerpo).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        vender(VENDEDOR, clave, cuerpo.replace("100.00", "100"))
                .andExpect(status().isCreated()).andExpect(content().json(original));
    }

    private String abrirTurno(String usuario) throws Exception {
        return abrirTurno(usuario, TERMINAL);
    }

    private String abrirTurno(String usuario, String terminal) throws Exception {
        String resultado = mvc.perform(post("/api/v1/retail/caja/turnos")
                        .with(jwt().jwt(token -> token.subject(usuario)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalPos\":\"" + terminal + "\",\"saldoInicialEfectivo\":200.00}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(resultado, "$.turnoId");
    }

    private ResultActions vender(String usuario, String clave, String cuerpo) throws Exception {
        return mvc.perform(post("/api/v1/retail/ventas")
                .with(jwt().jwt(token -> token.subject(usuario)))
                .header("Idempotency-Key", clave).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private String venta(String turnoId) {
        return """
                {"terminalPos":"%s","turnoId":"%s",
                 "items":[{"sku":"SKU-POLO","cantidad":1,"precioFinal":100.00}],
                 "pago":{"medioPago":"EFECTIVO","montoRecibidoEfectivo":100.00},
                 "comprobante":{"tipo":"BOLETA"},"emitirTicketRegalo":false}
                """.formatted(TERMINAL, turnoId);
    }
}

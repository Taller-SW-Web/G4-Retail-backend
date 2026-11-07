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

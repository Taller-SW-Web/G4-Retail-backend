package pe.edu.unmsm.fisi.retail.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoints del control de caja (F8): RF-20, RF-21 y RF-22.
 *
 * <p>Criterios de aceptación verificados de punta a punta: sin token no se toca el efectivo; abrir un
 * turno responde {@code 201}; repetir la apertura en la misma terminal responde {@code 409}; un
 * egreso sin respaldo responde {@code 422}; un turno inexistente responde {@code 404}; y un cierre
 * cuadrado responde {@code 200 CERRADA}.</p>
 *
 * <p>Las rutas son las del documento de reparto: el turno es un subrecurso
 * ({@code /caja/turnos/{turnoId}/...}), así que cada prueba abre el turno primero y se queda con su
 * identificador. Los datos de personal vienen de {@code datos-local.sql} (extracto del seed de
 * Angie); la clase es transaccional, de modo que cada prueba deshace lo que creó.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@Transactional
class CajaControllerTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String TERMINAL = "POS-01";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Sin token responde 401 en formato problem+json")
    void sinTokenResponde401() throws Exception {
        mvc.perform(get("/api/v1/retail/caja/turnos/actual"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALIDO"));
    }

    @Test
    @DisplayName("Sin turno abierto, la consulta responde 200 con estado CERRADA")
    void sinTurnoRespondeCerrada() throws Exception {
        mvc.perform(get("/api/v1/retail/caja/turnos/actual").with(conToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CERRADA"))
                .andExpect(jsonPath("$.turnoId").doesNotExist());
    }

    @Test
    @DisplayName("La apertura del turno responde 201 con el estado ABIERTA")
    void abreElTurno() throws Exception {
        mvc.perform(post("/api/v1/retail/caja/turnos")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalPos\":\"POS-01\",\"saldoInicialEfectivo\":200.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turnoId").exists())
                .andExpect(jsonPath("$.estado").value("ABIERTA"))
                .andExpect(jsonPath("$.cajero").value("VEN-001"));
    }

    @Test
    @DisplayName("El turno recien abierto se ve en la consulta del turno actual")
    void elTurnoAbiertoSeVeEnLaConsulta() throws Exception {
        abrirTurno();

        mvc.perform(get("/api/v1/retail/caja/turnos/actual").with(conToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ABIERTA"))
                .andExpect(jsonPath("$.terminalPos").value(TERMINAL))
                .andExpect(jsonPath("$.saldoInicialEfectivo").value(200.00));
    }

    @Test
    @DisplayName("Abrir dos veces la misma terminal responde 409")
    void noAbreDosVecesLaMismaTerminal() throws Exception {
        abrirTurno();

        mvc.perform(post("/api/v1/retail/caja/turnos")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalPos\":\"POS-01\",\"saldoInicialEfectivo\":100.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAJA_YA_ABIERTA"));
    }

    @Test
    @DisplayName("Un fondo fijo negativo responde 400 con el campo en errores[]")
    void rechazaFondoNegativo() throws Exception {
        mvc.perform(post("/api/v1/retail/caja/turnos")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalPos\":\"POS-01\",\"saldoInicialEfectivo\":-10.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"))
                .andExpect(jsonPath("$.errores[0].campo").value("saldoInicialEfectivo"));
    }

    @Test
    @DisplayName("El cierre cuadrado responde 200 con estado CERRADA")
    void cierraLaCajaCuadrada() throws Exception {
        String turnoId = abrirTurno();

        mvc.perform(post("/api/v1/retail/caja/turnos/" + turnoId + "/cierre")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montoDeclarado\":200.00,\"observaciones\":\"Caja cuadrada\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnoId").value(turnoId))
                .andExpect(jsonPath("$.saldoSistema").value(200.00))
                .andExpect(jsonPath("$.diferencia").value(0.00))
                .andExpect(jsonPath("$.estado").value("CERRADA"));
    }

    @Test
    @DisplayName("Un faltante responde 200 con estado OBSERVADA")
    void unFaltanteDejaLaSesionObservada() throws Exception {
        String turnoId = abrirTurno();

        mvc.perform(post("/api/v1/retail/caja/turnos/" + turnoId + "/cierre")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montoDeclarado\":175.00,\"observaciones\":\"Falta sencillo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diferencia").value(-25.00))
                .andExpect(jsonPath("$.estado").value("OBSERVADA"));
    }

    @Test
    @DisplayName("Un egreso mayor al efectivo en gaveta responde 422")
    void rechazaEgresoSinRespaldo() throws Exception {
        String turnoId = abrirTurno();

        mvc.perform(post("/api/v1/retail/caja/turnos/" + turnoId + "/movimientos")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoMovimiento\":\"SALIDA_GASTO\",\"monto\":500.00,"
                                + "\"motivo\":\"Compra de insumos\",\"autorizadoPor\":\"SUP-001\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FONDOS_INSUFICIENTES_EN_GAVETA"));
    }

    @Test
    @DisplayName("El ingreso menor acreditado se refleja en el Reporte Z")
    void elIngresoMenorSumaAlSistema() throws Exception {
        String turnoId = abrirTurno();

        mvc.perform(post("/api/v1/retail/caja/turnos/" + turnoId + "/movimientos")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoMovimiento\":\"INGRESO_MENOR\",\"monto\":50.00,"
                                + "\"motivo\":\"Reposicion de sencillo\",\"autorizadoPor\":\"SUP-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movimientoId").exists())
                .andExpect(jsonPath("$.nuevoSaldoEstimado").value(250.00));

        mvc.perform(post("/api/v1/retail/caja/turnos/" + turnoId + "/cierre")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montoDeclarado\":250.00,\"observaciones\":\"Con ingreso\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingresosMenores").value(50.00))
                .andExpect(jsonPath("$.saldoSistema").value(250.00))
                .andExpect(jsonPath("$.estado").value("CERRADA"));
    }

    @Test
    @DisplayName("Operar un turno que no existe responde 404")
    void turnoInexistenteResponde404() throws Exception {
        mvc.perform(post("/api/v1/retail/caja/turnos/cccccccc-0000-0000-0000-00000000dead/cierre")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montoDeclarado\":200.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TURNO_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Un turno ya cerrado no admite un segundo cierre")
    void noCierraDosVecesElMismoTurno() throws Exception {
        String turnoId = abrirTurno();

        mvc.perform(post("/api/v1/retail/caja/turnos/" + turnoId + "/cierre")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montoDeclarado\":200.00}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/retail/caja/turnos/" + turnoId + "/cierre")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montoDeclarado\":200.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TURNO_YA_CERRADO"));
    }

    /** Abre el turno de la terminal de prueba y devuelve su {@code turnoId}. */
    private String abrirTurno() throws Exception {
        String respuesta = mvc.perform(post("/api/v1/retail/caja/turnos")
                        .with(conToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalPos\":\"POS-01\",\"saldoInicialEfectivo\":200.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(respuesta, "$.turnoId");
    }

    /** Token válido del vendedor semilla; su {@code sub} coincide con {@code datos-local.sql}. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor conToken() {
        return jwt().jwt(token -> token.subject(VENDEDOR));
    }
}

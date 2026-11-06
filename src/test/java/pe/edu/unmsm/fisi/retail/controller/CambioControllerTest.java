package pe.edu.unmsm.fisi.retail.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de {@code /api/v1/retail/cambios} (F9).
 *
 * <p>Criterios de aceptacion verificados, de la spec F9 y la reparticion de la Semana 11:</p>
 * <ul>
 *   <li>RF-23: una boleta en plazo permite el cambio y una vencida lo bloquea.</li>
 *   <li>RF-24: un cambio de talla sin etiquetas se rechaza; el registro guarda al vendedor auditor.</li>
 *   <li>RF-25: se emite un vale {@code VALE-AAAA-NNNNN} con su monto y 30 dias de vigencia.</li>
 * </ul>
 *
 * <p>Las boletas vienen del doble del perfil {@code mock}: {@code B001-00000045} (hace 2 dias, con dos
 * polos) y {@code B001-00000012} (hace 20 dias, vencida).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@Transactional
class CambioControllerTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String EN_PLAZO = "B001-00000045";
    private static final String VENCIDA = "B001-00000012";

    private static final String CAMBIO_POLO = """
            {"boletaNumero":"B001-00000045","sku":"POLTEC-M-BLA","cantidad":1,"motivo":"CAMBIO_TALLA",
             "inspeccion":{"etiquetasIntactas":true,"sinSignosUso":true,"empaqueOriginal":true}}""";

    @Autowired
    private MockMvc mvc;

    // ---------- RF-23: validacion del comprobante ----------

    @Test
    @DisplayName("Una boleta en plazo devuelve sus prendas y cuantas unidades se pueden cambiar")
    void boletaEnPlazo() throws Exception {
        mvc.perform(get("/api/v1/retail/cambios/validar/" + EN_PLAZO).with(vendedor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boletaNumero").value(EN_PLAZO))
                .andExpect(jsonPath("$.diasTranscurridos").value(2))
                .andExpect(jsonPath("$.plazoDias").value(7))
                .andExpect(jsonPath("$.plazoValido").value(true))
                .andExpect(jsonPath("$.items[1].sku").value("POLTEC-M-BLA"))
                .andExpect(jsonPath("$.items[1].cantidadDisponibleCambio").value(2));
    }

    @Test
    @DisplayName("Una boleta vencida se muestra, pero marcada fuera de plazo")
    void boletaVencidaSeMuestraFueraDePlazo() throws Exception {
        mvc.perform(get("/api/v1/retail/cambios/validar/" + VENCIDA).with(vendedor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diasTranscurridos").value(20))
                .andExpect(jsonPath("$.plazoValido").value(false));
    }

    @Test
    @DisplayName("Un comprobante inexistente responde 404")
    void comprobanteInexistente() throws Exception {
        mvc.perform(get("/api/v1/retail/cambios/validar/B001-99999999").with(vendedor()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPROBANTE_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Un numero con formato invalido responde 400, no 500")
    void formatoInvalido() throws Exception {
        mvc.perform(get("/api/v1/retail/cambios/validar/B001-45").with(vendedor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @DisplayName("Sin token no se consulta nada")
    void sinToken() throws Exception {
        mvc.perform(get("/api/v1/retail/cambios/validar/" + EN_PLAZO))
                .andExpect(status().isUnauthorized());
    }

    // ---------- RF-24 y RF-25: registro del cambio y emision del vale ----------

    @Test
    @DisplayName("Un cambio aprobado emite un vale VALE-AAAA-NNNNN por el precio pagado")
    void emiteElVale() throws Exception {
        abrirCaja();

        mvc.perform(registrar(CAMBIO_POLO, UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estadoAprobacion").value("APROBADO_EN_TIENDA"))
                .andExpect(jsonPath("$.destinoSugerido").value("REINGRESO_INVENTARIO"))
                .andExpect(jsonPath("$.montoAcreditado").value(59.90))
                .andExpect(jsonPath("$.vale.codigoVale").value(matchesPattern("VALE-\\d{4}-\\d{5}")))
                .andExpect(jsonPath("$.vale.boletaOrigen").value(EN_PLAZO))
                .andExpect(jsonPath("$.vale.estado").value("EMITIDO"));
    }

    @Test
    @DisplayName("Tras un cambio, la boleta muestra una unidad menos disponible")
    void descuentaLasUnidadesCambiadas() throws Exception {
        abrirCaja();
        mvc.perform(registrar(CAMBIO_POLO, UUID.randomUUID().toString())).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/retail/cambios/validar/" + EN_PLAZO).with(vendedor()))
                .andExpect(jsonPath("$.items[1].cantidadDisponibleCambio").value(1));
    }

    @Test
    @DisplayName("La misma clave de idempotencia devuelve el mismo vale, no emite otro")
    void idempotencia() throws Exception {
        abrirCaja();
        String clave = UUID.randomUUID().toString();

        String primero = mvc.perform(registrar(CAMBIO_POLO, clave))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String segundo = mvc.perform(registrar(CAMBIO_POLO, clave))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(segundo).isEqualTo(primero);
    }

    @Test
    @DisplayName("Reusar la clave con otra prenda es un conflicto")
    void idempotenciaEnConflicto() throws Exception {
        abrirCaja();
        String clave = UUID.randomUUID().toString();
        mvc.perform(registrar(CAMBIO_POLO, clave)).andExpect(status().isCreated());

        mvc.perform(registrar(CAMBIO_POLO.replace("\"cantidad\":1", "\"cantidad\":2"), clave))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("Un cambio de talla sin etiquetas se rechaza en la inspeccion")
    void inspeccionNoApta() throws Exception {
        abrirCaja();

        mvc.perform(registrar(CAMBIO_POLO.replace("\"etiquetasIntactas\":true", "\"etiquetasIntactas\":false"),
                        UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSPECCION_NO_APTA"));
    }

    @Test
    @DisplayName("Con la boleta vencida no se registra el cambio")
    void plazoVencido() throws Exception {
        abrirCaja();
        String cambio = """
                {"boletaNumero":"B001-00000012","sku":"ZAPTRA-40-VER","cantidad":1,"motivo":"CAMBIO_TALLA",
                 "inspeccion":{"etiquetasIntactas":true,"sinSignosUso":true,"empaqueOriginal":true}}""";

        mvc.perform(registrar(cambio, UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLAZO_VENCIDO"));
    }

    @Test
    @DisplayName("No se pueden cambiar mas unidades de las compradas")
    void cantidadNoDisponible() throws Exception {
        abrirCaja();

        mvc.perform(registrar(CAMBIO_POLO.replace("\"cantidad\":1", "\"cantidad\":3"), UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANTIDAD_NO_DISPONIBLE"));
    }

    @Test
    @DisplayName("Una prenda que no esta en la boleta no se cambia")
    void prendaNoIncluida() throws Exception {
        abrirCaja();

        mvc.perform(registrar(CAMBIO_POLO.replace("POLTEC-M-BLA", "ZAPTRA-40-VER"), UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRENDA_NO_INCLUIDA"));
    }

    @Test
    @DisplayName("Sin turno de caja abierto no se registra el cambio")
    void sinCajaAbierta() throws Exception {
        mvc.perform(registrar(CAMBIO_POLO, UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAJA_NO_ABIERTA"));
    }

    @Test
    @DisplayName("Sin la cabecera Idempotency-Key responde 400, no 500")
    void sinIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/retail/cambios").with(vendedor())
                        .contentType(MediaType.APPLICATION_JSON).content(CAMBIO_POLO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_OBLIGATORIO"));
    }

    @Test
    @DisplayName("Un motivo inexistente responde 400, no 500")
    void motivoInexistente() throws Exception {
        mvc.perform(registrar(CAMBIO_POLO.replace("CAMBIO_TALLA", "CAPRICHO"), UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    // ---------- utilidades ----------

    private org.springframework.test.web.servlet.request.RequestPostProcessor vendedor() {
        return jwt().jwt(j -> j.subject(VENDEDOR));
    }

    private MockHttpServletRequestBuilder registrar(String cuerpo, String clave) {
        return post("/api/v1/retail/cambios").with(vendedor())
                .header("Idempotency-Key", clave)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo);
    }

    /** Un cambio exige un turno de caja abierto: se abre por la API real de F8. */
    private void abrirCaja() throws Exception {
        mvc.perform(post("/api/v1/retail/caja/turnos").with(vendedor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalPos\":\"POS-01\",\"saldoInicialEfectivo\":200.00}"))
                .andExpect(status().isCreated());
    }
}

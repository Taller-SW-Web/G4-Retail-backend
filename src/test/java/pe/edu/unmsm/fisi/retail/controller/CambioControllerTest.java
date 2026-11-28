package pe.edu.unmsm.fisi.retail.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.model.SolicitudCambio;
import pe.edu.unmsm.fisi.retail.repository.SolicitudCambioItemRepository;
import pe.edu.unmsm.fisi.retail.repository.SolicitudCambioRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de {@code /api/v1/retail/cambios} (F9, contrato 5.9: cambio directo de prenda).
 *
 * <p>Las boletas vienen del doble del perfil {@code mock}: {@code B001-00000045} (hace 2 dias: dos polos
 * POLTEC-M-BLA a S/ 59.90 y una zapatilla ZAPVEL-38-NEG a S/ 249.00) y {@code B001-00000012} (hace 20
 * dias, vencida). El pedido a Ventas lo atiende el doble de Ventas del mismo perfil.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@Transactional
class CambioControllerTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String EN_PLAZO = "B001-00000045";
    private static final String VENCIDA = "B001-00000012";
    private static final String INSPECCION_OK = "{\"etiquetasIntactas\":true,\"sinSignosUso\":true,\"empaqueOriginal\":true}";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private SolicitudCambioRepository solicitudes;
    @Autowired private SolicitudCambioItemRepository itemsNuevos;

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

    // ---------- RF-25: cambio directo segun el valor ----------

    @Test
    @DisplayName("Mismo modelo en otra talla: mismo valor, sin cobro ni boleta, con constancia y pedido en Ventas")
    void mismoModeloEsMismoValor() throws Exception {
        String turno = abrirCaja();

        mvc.perform(registrar(cambio(turno, "POLTEC-M-BLA", 1, "POLTEC-L-AZU", 1, null), clave()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipoCambio").value("MISMO_VALOR"))
                .andExpect(jsonPath("$.numeroConstancia").value(matchesPattern("CAMB-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.boletaOrigen").value(EN_PLAZO))
                .andExpect(jsonPath("$.montoCredito").value(59.90))
                .andExpect(jsonPath("$.totalNuevo").value(59.90))
                .andExpect(jsonPath("$.diferencia").value(0))
                .andExpect(jsonPath("$.pago").value(nullValue()))
                .andExpect(jsonPath("$.comprobante").value(nullValue()))
                .andExpect(jsonPath("$.pedidoId").value(matchesPattern("PED-\\d{4}-\\d{5}")))
                .andExpect(jsonPath("$.estadoInventario").value("CONFIRMADO"));
    }

    @Test
    @DisplayName("Mayor valor: se cobra solo la diferencia y se emite la boleta por el total nuevo")
    void mayorValorCobraLaDiferencia() throws Exception {
        String turno = abrirCaja();
        String pago = """
                ,"pagoDiferencia":{"medioPago":"EFECTIVO","montoRecibidoEfectivo":30.00},
                "comprobante":{"tipo":"BOLETA"}""";

        mvc.perform(registrar(cambio(turno, "POLTEC-M-BLA", 1, "TOPDE-M-NEG", 1, pago), clave()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipoCambio").value("MAYOR_VALOR"))
                .andExpect(jsonPath("$.montoCredito").value(59.90))
                .andExpect(jsonPath("$.totalNuevo").value(89.00))
                .andExpect(jsonPath("$.diferencia").value(29.10))
                .andExpect(jsonPath("$.pago.medioPago").value("EFECTIVO"))
                .andExpect(jsonPath("$.pago.montoRecibido").value(30.00))
                .andExpect(jsonPath("$.pago.vuelto").value(0.90))
                .andExpect(jsonPath("$.comprobante.serie").value("B001"))
                .andExpect(jsonPath("$.comprobante.total").value(89.00))
                .andExpect(jsonPath("$.estadoInventario").value("CONFIRMADO"));
    }

    @Test
    @DisplayName("Mayor valor sin indicar el pago de la diferencia responde 400")
    void mayorValorSinPago() throws Exception {
        String turno = abrirCaja();

        mvc.perform(registrar(cambio(turno, "POLTEC-M-BLA", 1, "TOPDE-M-NEG", 1, null), clave()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @DisplayName("Mayor valor con efectivo insuficiente para la diferencia se rechaza, como en el cobro de F5")
    void mayorValorConMontoInsuficiente() throws Exception {
        String turno = abrirCaja();
        String pago = """
                ,"pagoDiferencia":{"medioPago":"EFECTIVO","montoRecibidoEfectivo":10.00},
                "comprobante":{"tipo":"BOLETA"}""";

        mvc.perform(registrar(cambio(turno, "POLTEC-M-BLA", 1, "TOPDE-M-NEG", 1, pago), clave()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MONTO_INSUFICIENTE"));
    }

    @Test
    @DisplayName("Menor valor se rechaza: no hay vales ni devolucion de dinero")
    void menorValorSeRechaza() throws Exception {
        String turno = abrirCaja();

        mvc.perform(registrar(cambio(turno, "ZAPVEL-38-NEG", 1, "POLTEC-S-BLA", 1, null), clave()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAMBIO_MENOR_VALOR"));
    }

    @Test
    @DisplayName("El cambio guarda las prendas nuevas y, tras el, la boleta muestra una unidad menos")
    void guardaLasPrendasYDescuentaUnidades() throws Exception {
        String turno = abrirCaja();
        String clave = clave();
        mvc.perform(registrar(cambio(turno, "POLTEC-M-BLA", 1, "POLTEC-L-AZU", 1, null), clave))
                .andExpect(status().isCreated());

        SolicitudCambio solicitud = solicitudes.findByIdempotencyKey(clave).orElseThrow();
        assertThat(itemsNuevos.findBySolicitudId(solicitud.getIdSolicitud()))
                .singleElement()
                .satisfies(item -> assertThat(item.getSku()).isEqualTo("POLTEC-L-AZU"));
        assertThat(solicitud.getValeTemporalCodigo()).isNull();

        mvc.perform(get("/api/v1/retail/cambios/validar/" + EN_PLAZO).with(vendedor()))
                .andExpect(jsonPath("$.items[1].cantidadDisponibleCambio").value(1));
    }

    // ---------- idempotencia ----------

    @Test
    @DisplayName("La misma clave devuelve el mismo cambio y no consume otra unidad")
    void idempotencia() throws Exception {
        String turno = abrirCaja();
        String clave = clave();
        String cuerpo = cambio(turno, "POLTEC-M-BLA", 1, "POLTEC-L-AZU", 1, null);

        String primero = mvc.perform(registrar(cuerpo, clave)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String segundo = mvc.perform(registrar(cuerpo, clave)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(segundo)).isEqualTo(json.readTree(primero));
        mvc.perform(get("/api/v1/retail/cambios/validar/" + EN_PLAZO).with(vendedor()))
                .andExpect(jsonPath("$.items[1].cantidadDisponibleCambio").value(1));
    }

    @Test
    @DisplayName("Reusar la clave con otra peticion es un conflicto")
    void idempotenciaEnConflicto() throws Exception {
        String turno = abrirCaja();
        String clave = clave();
        mvc.perform(registrar(cambio(turno, "POLTEC-M-BLA", 1, "POLTEC-L-AZU", 1, null), clave))
                .andExpect(status().isCreated());

        mvc.perform(registrar(cambio(turno, "POLTEC-M-BLA", 1, "POLTEC-S-BLA", 1, null), clave))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    // ---------- reglas que se mantienen: plazo, inspeccion y unidades ----------

    @Test
    @DisplayName("Un cambio de talla sin etiquetas se rechaza en la inspeccion")
    void inspeccionNoApta() throws Exception {
        String turno = abrirCaja();

        mvc.perform(registrar(cambio(turno, "POLTEC-M-BLA", 1, "POLTEC-L-AZU", 1, null)
                        .replace("\"etiquetasIntactas\":true", "\"etiquetasIntactas\":false"), clave()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSPECCION_NO_APTA"));
    }

    @Test
    @DisplayName("Una falla de fabrica se deriva a Postventa")
    void fallaDeFabrica() throws Exception {
        String turno = abrirCaja();

        mvc.perform(registrar(cambio(turno, "POLTEC-M-BLA", 1, "POLTEC-L-AZU", 1, null)
                        .replace("CAMBIO_TALLA", "FALLA_FABRICA"), clave()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GARANTIA_REQUIERE_POSTVENTA"));
    }

    @Test
    @DisplayName("Con la boleta vencida no se registra el cambio")
    void plazoVencido() throws Exception {
        String turno = abrirCaja();

        mvc.perform(registrar(cambio(turno, "ZAPTRA-40-VER", 1, "ZAPTRA-41-VER", 1, null)
                        .replace(EN_PLAZO, VENCIDA), clave()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLAZO_VENCIDO"));
    }

    @Test
    @DisplayName("No se pueden cambiar mas unidades de las compradas")
    void cantidadNoDisponible() throws Exception {
        String turno = abrirCaja();

        mvc.perform(registrar(cambio(turno, "POLTEC-M-BLA", 3, "POLTEC-L-AZU", 3, null), clave()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANTIDAD_NO_DISPONIBLE"));
    }

    @Test
    @DisplayName("Una prenda que no esta en la boleta no se cambia")
    void prendaNoIncluida() throws Exception {
        String turno = abrirCaja();

        mvc.perform(registrar(cambio(turno, "ZAPTRA-40-VER", 1, "ZAPTRA-41-VER", 1, null), clave()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRENDA_NO_INCLUIDA"));
    }

    // ---------- turno y errores de entrada ----------

    @Test
    @DisplayName("Con un turno de caja que no existe no se registra el cambio, como en el cobro de F5")
    void sinTurnoAbierto() throws Exception {
        mvc.perform(registrar(cambio(UUID.randomUUID().toString(), "POLTEC-M-BLA", 1, "POLTEC-L-AZU", 1, null),
                        clave()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TURNO_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Sin la cabecera Idempotency-Key responde 400, no 500")
    void sinIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/retail/cambios").with(vendedor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cambio(UUID.randomUUID().toString(), "POLTEC-M-BLA", 1, "POLTEC-L-AZU", 1, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_OBLIGATORIO"));
    }

    @Test
    @DisplayName("Un motivo inexistente responde 400, no 500")
    void motivoInexistente() throws Exception {
        mvc.perform(registrar(cambio(UUID.randomUUID().toString(), "POLTEC-M-BLA", 1, "POLTEC-L-AZU", 1, null)
                        .replace("CAMBIO_TALLA", "CAPRICHO"), clave()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    // ---------- utilidades ----------

    /** Cuerpo del contrato 5.9; {@code extra} agrega pagoDiferencia y comprobante cuando hacen falta. */
    private static String cambio(String turno, String devuelto, int cantidadDevuelta, String nuevo, int cantidadNueva,
                                 String extra) {
        return """
                {"boletaNumero":"%s","terminalPos":"POS-01","turnoId":"%s","motivo":"CAMBIO_TALLA",
                 "devuelto":{"sku":"%s","cantidad":%d,"inspeccion":%s},
                 "nuevos":[{"sku":"%s","cantidad":%d}]%s}""".formatted(EN_PLAZO, turno, devuelto, cantidadDevuelta,
                INSPECCION_OK, nuevo, cantidadNueva, extra == null ? "" : extra);
    }

    private static String clave() {
        return UUID.randomUUID().toString();
    }

    private RequestPostProcessor vendedor() {
        return jwt().jwt(j -> j.subject(VENDEDOR));
    }

    private MockHttpServletRequestBuilder registrar(String cuerpo, String clave) {
        return post("/api/v1/retail/cambios").with(vendedor())
                .header("Idempotency-Key", clave)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo);
    }

    /** Un cambio exige un turno de caja abierto: se abre por la API real de F8. Devuelve su turnoId. */
    private String abrirCaja() throws Exception {
        String respuesta = mvc.perform(post("/api/v1/retail/caja/turnos").with(vendedor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalPos\":\"POS-01\",\"saldoInicialEfectivo\":200.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(respuesta).path("turnoId").asText();
    }
}

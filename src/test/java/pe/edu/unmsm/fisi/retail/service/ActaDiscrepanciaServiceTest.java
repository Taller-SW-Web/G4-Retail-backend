package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acta de merma (F12, RF-34, contrato 5.12), de punta a punta sobre la base local.
 *
 * <p>Las incidencias se insertan directo en {@code RET_INCIDENCIA_INVENTARIO}: el reporte de incidencias
 * es de otra tarea de F12.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
@Transactional
class ActaDiscrepanciaServiceTest {

    private static final String SUPERVISOR = "bbbbbbbb-0000-0000-0000-000000000004";
    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    /** Codigo de sucursal de la tienda del supervisor: el perfil guarda el UUID 1111..., la incidencia el codigo. */
    private static final String TIENDA = "TIENDA-MIRAFLORES";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;

    @Test
    @DisplayName("CA-RF34-01 y 03: con devolucion central emite el acta numerada y las prendas pasan a DERIVADO_ALMACEN")
    void emiteElActaConDevolucionCentral() throws Exception {
        UUID mancha = incidencia("POLTEC-M-BLA", "MANCHADO_PROBADOR", "EN_CUARENTENA", TIENDA);
        UUID costura = incidencia("ZAPVEL-38-NEG", "COSTURA_ROTA", "EN_CUARENTENA", TIENDA);

        mvc.perform(emitir(SUPERVISOR, "[\"%s\",\"%s\"]".formatted(mancha, costura), "DEVOLUCION_CENTRAL"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actaNumero").value(matchesPattern("ACTA-MERMA-\\d{4}-\\d{4}")))
                .andExpect(jsonPath("$.fechaEmision").isNotEmpty())
                .andExpect(jsonPath("$.tiendaId").value(TIENDA))
                .andExpect(jsonPath("$.tipoDestino").value("DEVOLUCION_CENTRAL"))
                .andExpect(jsonPath("$.observaciones").value("Lote enviado con transporte interno"))
                .andExpect(jsonPath("$.supervisor.nombre").value("SUP-001"))
                .andExpect(jsonPath("$.totalPrendas").value(2))
                .andExpect(jsonPath("$.incidencias[0].sku").value("POLTEC-M-BLA"))
                .andExpect(jsonPath("$.incidencias[0].tipoFalla").value("MANCHADO_PROBADOR"))
                .andExpect(jsonPath("$.incidencias[0].detalleObservacion").value("Detalle de POLTEC-M-BLA"))
                .andExpect(jsonPath("$.incidencias[0].estado").value("DERIVADO_ALMACEN"));

        assertThat(estado(mancha)).isEqualTo("DERIVADO_ALMACEN");
        assertThat(jdbc.sql("SELECT acta_id FROM RET_INCIDENCIA_INVENTARIO WHERE id_incidencia = ?")
                .param(costura).query(UUID.class).single()).isNotNull();
    }

    @Test
    @DisplayName("Con baja definitiva o ajuste por perdida las prendas quedan en BAJA_DEFINITIVA")
    void bajaYAjusteQuedanEnBajaDefinitiva() throws Exception {
        UUID baja = incidencia("POLTEC-M-BLA", "MANCHADO_PROBADOR", "EN_CUARENTENA", TIENDA);
        UUID perdida = incidencia("CASROM-M-AZU", "EXTRAVIO_NO_UBICADO", "EN_CUARENTENA", TIENDA);

        mvc.perform(emitir(SUPERVISOR, "[\"%s\"]".formatted(baja), "BAJA_DEFINITIVA")).andExpect(status().isCreated());
        mvc.perform(emitir(SUPERVISOR, "[\"%s\"]".formatted(perdida), "AJUSTE_PERDIDA"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.incidencias[0].estado").value("BAJA_DEFINITIVA"));

        assertThat(estado(baja)).isEqualTo("BAJA_DEFINITIVA");
        assertThat(estado(perdida)).isEqualTo("BAJA_DEFINITIVA");
    }

    @Test
    @DisplayName("Cada acta toma un numero nuevo")
    void numerosCorrelativos() throws Exception {
        UUID primera = incidencia("POLTEC-M-BLA", "MANCHADO_PROBADOR", "EN_CUARENTENA", TIENDA);
        UUID segunda = incidencia("POLTEC-S-BLA", "MANCHADO_PROBADOR", "EN_CUARENTENA", TIENDA);

        String a = numero(mvc.perform(emitir(SUPERVISOR, "[\"%s\"]".formatted(primera), "DEVOLUCION_CENTRAL"))
                .andReturn().getResponse().getContentAsString());
        String b = numero(mvc.perform(emitir(SUPERVISOR, "[\"%s\"]".formatted(segunda), "DEVOLUCION_CENTRAL"))
                .andReturn().getResponse().getContentAsString());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("CA-RF34-04: sin prendas responde 400 LISTA_VACIA")
    void listaVacia() throws Exception {
        mvc.perform(emitir(SUPERVISOR, "[]", "DEVOLUCION_CENTRAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LISTA_VACIA"));
    }

    @Test
    @DisplayName("CA-RF34-04: sin el campo incidenciasIds tambien responde 400 LISTA_VACIA, no 500")
    void sinCampoIncidencias() throws Exception {
        mvc.perform(post("/api/v1/retail/inventario/actas-merma").with(jwt().jwt(j -> j.subject(SUPERVISOR)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tipoDestino":"DEVOLUCION_CENTRAL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LISTA_VACIA"));
    }

    @Test
    @DisplayName("Una prenda que Inventory aun no acepta (PENDIENTE_REPORTE) no entra: 409 y nada cambia")
    void pendienteDeReporteNoEntra() throws Exception {
        UUID lista = incidencia("POLTEC-M-BLA", "MANCHADO_PROBADOR", "EN_CUARENTENA", TIENDA);
        UUID pendiente = incidencia("ZAPVEL-38-NEG", "COSTURA_ROTA", "PENDIENTE_REPORTE", TIENDA);

        mvc.perform(emitir(SUPERVISOR, "[\"%s\",\"%s\"]".formatted(lista, pendiente), "DEVOLUCION_CENTRAL"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENCIA_NO_EN_CUARENTENA"));

        assertThat(estado(lista)).isEqualTo("EN_CUARENTENA");
    }

    @Test
    @DisplayName("Una prenda ya liquidada no se despacha dos veces")
    void yaLiquidada() throws Exception {
        UUID prenda = incidencia("POLTEC-M-BLA", "MANCHADO_PROBADOR", "EN_CUARENTENA", TIENDA);
        mvc.perform(emitir(SUPERVISOR, "[\"%s\"]".formatted(prenda), "DEVOLUCION_CENTRAL")).andExpect(status().isCreated());

        mvc.perform(emitir(SUPERVISOR, "[\"%s\"]".formatted(prenda), "DEVOLUCION_CENTRAL"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENCIA_NO_EN_CUARENTENA"));
    }

    @Test
    @DisplayName("Una prenda de otra tienda no entra al acta")
    void otraTienda() throws Exception {
        UUID ajena = incidencia("POLTEC-M-BLA", "MANCHADO_PROBADOR", "EN_CUARENTENA", "TIENDA-OTRA");

        mvc.perform(emitir(SUPERVISOR, "[\"%s\"]".formatted(ajena), "DEVOLUCION_CENTRAL"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENCIA_NO_EN_CUARENTENA"));
    }

    @Test
    @DisplayName("Un vendedor que no es supervisor no emite actas")
    void soloSupervisor() throws Exception {
        UUID prenda = incidencia("POLTEC-M-BLA", "MANCHADO_PROBADOR", "EN_CUARENTENA", TIENDA);

        mvc.perform(emitir(VENDEDOR, "[\"%s\"]".formatted(prenda), "DEVOLUCION_CENTRAL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROL_NO_PERMITIDO"));
    }

    @Test
    @DisplayName("Un destino inexistente responde 400, no 500")
    void destinoInexistente() throws Exception {
        mvc.perform(emitir(SUPERVISOR, "[\"%s\"]".formatted(UUID.randomUUID()), "REGALAR"))
                .andExpect(status().isBadRequest());
    }

    // ---------- utilidades ----------

    private UUID incidencia(String sku, String tipoFalla, String estado, String tienda) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO RET_INCIDENCIA_INVENTARIO (id_incidencia, tienda_id, vendedor_reporta_id, variante_sku_id,
                    codigo_barras, tipo_falla, detalle_observacion, estado_cuarentena, fecha_reporte, updated_at)
                VALUES (?, ?, ?, ?, '7750000000000', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).params(id, tienda, UUID.fromString(VENDEDOR), sku, tipoFalla, "Detalle de " + sku, estado).update();
        return id;
    }

    private String estado(UUID id) {
        return jdbc.sql("SELECT estado_cuarentena FROM RET_INCIDENCIA_INVENTARIO WHERE id_incidencia = ?")
                .param(id).query(String.class).single();
    }

    private static final Pattern NUMERO_ACTA = Pattern.compile("\"actaNumero\":\"([^\"]+)\"");

    private static String numero(String respuesta) {
        Matcher m = NUMERO_ACTA.matcher(respuesta);
        assertThat(m.find()).as("la respuesta trae actaNumero").isTrue();
        return m.group(1);
    }

    private MockHttpServletRequestBuilder emitir(String usuario, String ids, String destino) {
        return post("/api/v1/retail/inventario/actas-merma").with(jwt().jwt(j -> j.subject(usuario)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"incidenciasIds":%s,"tipoDestino":"%s","observaciones":"Lote enviado con transporte interno"}
                        """.formatted(ids, destino));
    }
}

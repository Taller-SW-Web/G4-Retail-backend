package pe.edu.unmsm.fisi.retail.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.edu.unmsm.fisi.retail.dto.*;
import pe.edu.unmsm.fisi.retail.integracion.ventas.ContingenciaVentasPort;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.repository.*;
import pe.edu.unmsm.fisi.retail.service.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** CA-10-06/07: duplicados durables, aislamiento de filas y un solo cobro de efectivo. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:f10-pruebas;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
class ContingenciaControllerTest {
    static final UUID USUARIO = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CajaService caja;
    @Autowired SincronizacionOfflineService servicio;
    @Autowired ContingenciaLogRepository logs;
    @Autowired CobroTurnoRepository cobros;
    @MockitoBean ContingenciaVentasPort ventas;
    String terminal;
    String turno;

    @BeforeEach void preparar() {
        terminal = "F10-" + UUID.randomUUID().toString().substring(0, 8);
        turno = caja.abrir(USUARIO, new AperturaTurnoRequest(terminal, new BigDecimal("100.00"))).turnoId();
        when(ventas.registrar(any(), any(), anyString(), anyString())).thenReturn("PED-F10-PRUEBA");
    }

    Map<String,Object> venta() throws Exception {
        String uuid = UUID.randomUUID().toString();
        String fecha = java.time.Instant.now().toString();
        Map<String,Object> v = new LinkedHashMap<>();
        v.put("ventaLocalUuid", uuid); v.put("correlativoContingencia", "CONT-" + uuid);
        v.put("fechaHoraOffline", fecha);
        v.put("items", List.of(Map.of("sku", "SKU-PRUEBA", "cantidad", 1, "precioUnitario", 10)));
        v.put("totalNeto", 10); v.put("efectivoRecibido", 20); v.put("vuelto", 10);
        v.put("clienteId", null);
        // Vector independiente del código de producción, definido en el contrato F10 §7.
        var canon = Arrays.asList(terminal, turno, uuid, "CONT-" + uuid, fecha,
                List.of(List.of("SKU-PRUEBA", 1, 1000)), 1000, 2000, 1000, null);
        v.put("hashIntegridad", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(canon).getBytes(StandardCharsets.UTF_8))));
        return v;
    }
    String cuerpo(List<Map<String,Object>> lote) throws Exception {
        return json.writeValueAsString(Map.of("terminalPos", terminal, "turnoId", turno, "lote", lote));
    }
    String enviar(String cuerpo) throws Exception {
        return mvc.perform(post("/api/v1/retail/contingencia/sincronizar")
                .with(jwt().jwt(j -> j.subject(USUARIO.toString()))).contentType("application/json").content(cuerpo))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test void exigeSesionYLimitaLote() throws Exception {
        mvc.perform(post("/api/v1/retail/contingencia/sincronizar").contentType("application/json").content(cuerpo(List.of(venta()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/retail/contingencia/sincronizar").with(jwt()).contentType("application/json")
                .content(cuerpo(Collections.nCopies(51, venta())))).andExpect(status().isBadRequest());
    }
    @Test void reintentoDevuelveResultadoOriginalYUnSoloCobro() throws Exception {
        var v=venta(); String cuerpo=cuerpo(List.of(v));
        var primero=json.readTree(enviar(cuerpo)).at("/resultados/0");
        assertThat(primero.get("estado").asText()).isEqualTo("RESINCRONIZADO");
        assertThat(json.readTree(enviar(cuerpo)).at("/resultados/0/estado").asText()).isEqualTo("DUPLICADO");
        verify(ventas, times(1)).registrar(any(), any(), eq((String) v.get("ventaLocalUuid")), anyString());
        assertThat(logs.findByVentaLocalUuid((String)v.get("ventaLocalUuid"))).isPresent();
        assertThat(cobros.findByIdempotencyKey((String)v.get("ventaLocalUuid"))).isPresent();
        var cierre=caja.cerrar(USUARIO, UUID.fromString(turno), new CierreTurnoRequest(new BigDecimal("110.00"), "Prueba F10"));
        assertThat(cierre.saldoSistema()).isEqualByComparingTo("110.00");
        assertThat(json.readTree(enviar(cuerpo)).at("/resultados/0/estado").asText()).isEqualTo("DUPLICADO");
    }
    @Test void unaVentaCorruptaNoAbortaLaSiguiente() throws Exception {
        var corrupta=venta(); corrupta.put("hashIntegridad", "0".repeat(64));
        var valida=venta(); var respuesta=json.readTree(enviar(cuerpo(List.of(corrupta,valida))));
        assertThat(respuesta.at("/resultados/0/estado").asText()).isEqualTo("RECHAZADO");
        assertThat(respuesta.at("/resultados/1/estado").asText()).isEqualTo("RESINCRONIZADO");
    }
    @Test void falloRemotoConservaReservaYClaveAlReintentar() throws Exception {
        var v=venta(); var cuerpo=cuerpo(List.of(v));
        when(ventas.registrar(any(),any(),anyString(),anyString()))
                .thenThrow(new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE","Sin red"))
                .thenReturn("PED-RECUPERADO");
        assertThat(json.readTree(enviar(cuerpo)).at("/resultados/0/estado").asText()).isEqualTo("REQUIERE_REVISION");
        var reserva=logs.findByVentaLocalUuid((String)v.get("ventaLocalUuid")).orElseThrow();
        assertThat(cobros.findByIdempotencyKey((String)v.get("ventaLocalUuid"))).isEmpty();
        var resultado=json.readTree(enviar(cuerpo)).at("/resultados/0");
        assertThat(resultado.get("estado").asText()).isEqualTo("RESINCRONIZADO");
        assertThat(resultado.get("comprobante").asText()).isEqualTo("B010-"+String.format("%08d",reserva.getNumeroComprobante()));
    }
    @Test void dosSolicitudesConcurrentesNoDuplicanPedido() throws Exception {
        var request=json.readValue(cuerpo(List.of(venta())), SincronizacionOfflineRequest.class);
        var ejecutor=Executors.newFixedThreadPool(2);
        try {
            var tareas=ejecutor.invokeAll(List.<Callable<String>>of(
                () -> servicio.sincronizar(request,USUARIO).resultados().getFirst().estado(),
                () -> servicio.sincronizar(request,USUARIO).resultados().getFirst().estado()));
            assertThat(List.of(tareas.get(0).get(),tareas.get(1).get())).containsExactlyInAnyOrder("RESINCRONIZADO","DUPLICADO");
            verify(ventas,times(1)).registrar(any(),any(),anyString(),anyString());
        } finally { ejecutor.shutdownNow(); }
    }
    @Test void otraTerminalNoPuedeReatribuirLaVenta() throws Exception {
        var v=venta(); enviar(cuerpo(List.of(v)));
        terminal="OTRA-TERMINAL";
        assertThat(json.readTree(enviar(cuerpo(List.of(v)))).at("/resultados/0/estado").asText()).isEqualTo("RECHAZADO");
    }
}

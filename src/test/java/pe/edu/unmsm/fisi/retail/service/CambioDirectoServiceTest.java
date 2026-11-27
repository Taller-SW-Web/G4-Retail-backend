package pe.edu.unmsm.fisi.retail.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pe.edu.unmsm.fisi.retail.client.VentasClient;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;
import pe.edu.unmsm.fisi.retail.client.dto.VentasPedidoResponse;
import pe.edu.unmsm.fisi.retail.integracion.cambios.PedidosCambioPort;
import pe.edu.unmsm.fisi.retail.integracion.cambios.ResultadoPedidoCambio;
import pe.edu.unmsm.fisi.retail.model.EstadoInventarioCambio;
import pe.edu.unmsm.fisi.retail.repository.SolicitudCambioRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cambio directo (F9, contrato 5.9) sobre una boleta real: cada prueba vende dos polos por F5, que quedan en
 * RET_VENTA, y cambia sobre esa boleta. Las transacciones se confirman de verdad, para probar el bloqueo de la
 * boleta con dos cambios simultaneos; por eso usa una base propia.
 *
 * <p>Ventas (G5) se simula: {@link VentasClient} para la venta original y {@link PedidosCambioPort} para el
 * pedido del cambio, que es donde se prueban la caida y el rechazo de Ventas.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cambio-directo;MODE=PostgreSQL",
        "retail.cambios.reintento-pedidos-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
class CambioDirectoServiceTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String INSPECCION_OK = "{\"etiquetasIntactas\":true,\"sinSignosUso\":true,\"empaqueOriginal\":true}";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private CambioService cambios;
    @Autowired private SolicitudCambioRepository solicitudes;
    @MockitoBean private VentasClient ventas;
    @MockitoBean private PedidosCambioPort pedidosCambio;

    private JsonNode caja;
    private String boleta;

    @BeforeEach
    void venderDosPolos() throws Exception {
        when(ventas.crearPedido(any(), anyString())).thenAnswer(inv -> Optional.of(new VentasPedidoResponse(
                "PED-2026-" + UUID.randomUUID().toString().substring(0, 5), "ENTREGADO", new BigDecimal("119.80"),
                "PEN", "RETAIL", null)));
        caja = abrirCajaSiHaceFalta();
        String venta = """
                {"terminalPos":"%s","turnoId":"%s","items":[{"sku":"POLTEC-M-BLA","cantidad":2}],
                "pago":{"medioPago":"EFECTIVO","montoRecibidoEfectivo":200},"comprobante":{"tipo":"BOLETA"}}
                """.formatted(caja.path("terminalPos").asText(), caja.path("turnoId").asText());
        String respuesta = mvc.perform(post("/api/v1/retail/ventas").with(jwt().jwt(t -> t.subject(VENDEDOR)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(venta))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        boleta = "B001-" + json.readTree(respuesta).path("comprobante").path("correlativo").asText();
    }

    @Test
    @DisplayName("Mismo valor con Ventas caida: el cambio se confirma igual y queda PENDIENTE")
    void mismoValorConVentasCaidaQuedaPendiente() throws Exception {
        when(pedidosCambio.enviar(any(), anyString())).thenReturn(new ResultadoPedidoCambio.NoDisponible("timeout"));
        String clave = UUID.randomUUID().toString();

        mvc.perform(cambio(clave, "POLTEC-L-AZU", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipoCambio").value("MISMO_VALOR"))
                .andExpect(jsonPath("$.estadoInventario").value("PENDIENTE"))
                .andExpect(jsonPath("$.pedidoId").doesNotExist());

        var solicitud = solicitudes.findByIdempotencyKey(clave).orElseThrow();
        assertThat(solicitud.getEstadoInventario()).isEqualTo(EstadoInventarioCambio.PENDIENTE);
        assertThat(solicitud.getPedidoPendiente()).contains("CREDITO_CAMBIO");
    }

    @Test
    @DisplayName("El job reenvia el pedido pendiente con la misma clave y el reintento devuelve el pedido confirmado")
    void elJobConfirmaElPedidoPendiente() throws Exception {
        String clave = UUID.randomUUID().toString();
        when(pedidosCambio.enviar(any(), anyString()))
                .thenReturn(new ResultadoPedidoCambio.NoDisponible("timeout"))
                .thenReturn(new ResultadoPedidoCambio.Confirmado("PED-2026-77777", "PAGADO"));
        mvc.perform(cambio(clave, "POLTEC-L-AZU", null)).andExpect(status().isCreated());

        assertThat(cambios.reintentarPedidosPendientes()).isGreaterThanOrEqualTo(1);

        var enviados = ArgumentCaptor.forClass(VentasCrearPedidoRequest.class);
        var claves = ArgumentCaptor.forClass(String.class);
        verify(pedidosCambio, times(2)).enviar(enviados.capture(), claves.capture());
        assertThat(claves.getAllValues()).containsOnly(clave);
        assertThat(enviados.getAllValues().getLast()).isEqualTo(enviados.getAllValues().getFirst());
        assertThat(solicitudes.findByIdempotencyKey(clave).orElseThrow().getEstadoInventario())
                .isEqualTo(EstadoInventarioCambio.CONFIRMADO);

        mvc.perform(cambio(clave, "POLTEC-L-AZU", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estadoInventario").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pedidoId").value("PED-2026-77777"));
    }

    @Test
    @DisplayName("El pedido lleva el credito, el pedido de origen y la prenda devuelta, como acordo G5")
    void elPedidoLlevaElCreditoDelCambio() throws Exception {
        when(pedidosCambio.enviar(any(), anyString())).thenReturn(new ResultadoPedidoCambio.Confirmado("PED-2026-1", "PAGADO"));
        String pago = ",\"pagoDiferencia\":{\"medioPago\":\"EFECTIVO\",\"montoRecibidoEfectivo\":50},"
                + "\"comprobante\":{\"tipo\":\"BOLETA\"}";

        mvc.perform(cambio(UUID.randomUUID().toString(), "TOPDE-M-NEG", pago)).andExpect(status().isCreated());

        var enviado = ArgumentCaptor.forClass(VentasCrearPedidoRequest.class);
        verify(pedidosCambio).enviar(enviado.capture(), anyString());
        var p = enviado.getValue().pago();
        assertThat(p.metodoPago()).isEqualTo("CREDITO_CAMBIO");
        assertThat(p.montoCreditoCambio()).isEqualByComparingTo("59.90");
        assertThat(p.montoEfectivo()).isEqualByComparingTo("29.10");
        assertThat(p.montoTarjeta()).isNull();
        assertThat(p.montoCreditoCambio().add(p.montoEfectivo())).isEqualByComparingTo(p.total());
        assertThat(p.pedidoOrigenId()).startsWith("PED-2026-");
        assertThat(p.itemsDevueltos()).containsExactly(new VentasCrearPedidoRequest.ItemDevuelto("POLTEC-M-BLA", 1));
    }

    @Test
    @DisplayName("Mayor valor con Ventas caida responde 503 y no consume la unidad")
    void mayorValorConVentasCaida() throws Exception {
        when(pedidosCambio.enviar(any(), anyString())).thenReturn(new ResultadoPedidoCambio.NoDisponible("timeout"));
        String pago = ",\"pagoDiferencia\":{\"medioPago\":\"EFECTIVO\",\"montoRecibidoEfectivo\":50},"
                + "\"comprobante\":{\"tipo\":\"BOLETA\"}";

        mvc.perform(cambio(UUID.randomUUID().toString(), "TOPDE-M-NEG", pago))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("VENTAS_NO_DISPONIBLE"));

        mvc.perform(get("/api/v1/retail/cambios/validar/" + boleta).with(jwt().jwt(t -> t.subject(VENDEDOR))))
                .andExpect(jsonPath("$.items[0].cantidadDisponibleCambio").value(2));
    }

    @Test
    @DisplayName("Si Ventas rechaza el pedido de origen, responde 422 PEDIDO_NO_APTO con el motivo")
    void ventasRechazaElOrigen() throws Exception {
        when(pedidosCambio.enviar(any(), anyString()))
                .thenReturn(new ResultadoPedidoCambio.Rechazado("PEDIDO_ORIGEN_NO_ENTREGADO", "Unprocessable Entity"));

        mvc.perform(cambio(UUID.randomUUID().toString(), "POLTEC-L-AZU", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_APTO"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PEDIDO_ORIGEN_NO_ENTREGADO")));
    }

    @Test
    @DisplayName("Dos cambios a la vez sobre la misma boleta no superan las unidades compradas")
    void concurrenciaNoSuperaLoComprado() throws Exception {
        when(pedidosCambio.enviar(any(), anyString())).thenReturn(new ResultadoPedidoCambio.Confirmado("PED-2026-2", "PAGADO"));
        // La boleta tiene 2 polos: cada solicitud pide los 2. Solo una puede pasar.
        int solicitudesSimultaneas = 2;
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(solicitudesSimultaneas);
        List<Future<Integer>> estados = new ArrayList<>();
        for (int i = 0; i < solicitudesSimultaneas; i++) {
            Callable<Integer> tarea = () -> {
                largada.await();
                return mvc.perform(cambio(UUID.randomUUID().toString(), "POLTEC-L-AZU", null, 2))
                        .andReturn().getResponse().getStatus();
            };
            estados.add(hilos.submit(tarea));
        }
        largada.countDown();
        List<Integer> resultados = new ArrayList<>();
        for (Future<Integer> estado : estados) {
            resultados.add(estado.get());
        }
        hilos.shutdown();

        assertThat(resultados).containsExactlyInAnyOrder(201, 422);
        mvc.perform(get("/api/v1/retail/cambios/validar/" + boleta).with(jwt().jwt(t -> t.subject(VENDEDOR))))
                .andExpect(jsonPath("$.items[0].cantidadDisponibleCambio").value(0));
    }

    // ---------- utilidades ----------

    private MockHttpServletRequestBuilder cambio(String clave, String nuevo, String extra) {
        return cambio(clave, nuevo, extra, 1);
    }

    private MockHttpServletRequestBuilder cambio(String clave, String nuevo, String extra, int cantidad) {
        String cuerpo = """
                {"boletaNumero":"%s","terminalPos":"%s","turnoId":"%s","motivo":"CAMBIO_TALLA",
                 "devuelto":{"sku":"POLTEC-M-BLA","cantidad":%d,"inspeccion":%s},
                 "nuevos":[{"sku":"%s","cantidad":%d}]%s}""".formatted(boleta, caja.path("terminalPos").asText(),
                caja.path("turnoId").asText(), cantidad, INSPECCION_OK, nuevo, cantidad, extra == null ? "" : extra);
        return post("/api/v1/retail/cambios").with(jwt().jwt(t -> t.subject(VENDEDOR)))
                .header("Idempotency-Key", clave)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo);
    }

    /** La base es comun a las pruebas de esta clase: el turno se abre una vez y luego se reutiliza. */
    private JsonNode abrirCajaSiHaceFalta() throws Exception {
        var actual = json.readTree(mvc.perform(get("/api/v1/retail/caja/turnos/actual")
                .with(jwt().jwt(t -> t.subject(VENDEDOR)))).andReturn().getResponse().getContentAsString());
        if ("ABIERTA".equals(actual.path("estado").asText())) {
            return actual;
        }
        String abierto = mvc.perform(post("/api/v1/retail/caja/turnos").with(jwt().jwt(t -> t.subject(VENDEDOR)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalPos\":\"POS-CAMBIOS\",\"saldoInicialEfectivo\":200.00}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(abierto);
    }
}

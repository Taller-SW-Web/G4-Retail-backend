package pe.edu.unmsm.fisi.retail.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.edu.unmsm.fisi.retail.client.VentasClient;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;
import pe.edu.unmsm.fisi.retail.client.dto.VentasPedidoResponse;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
class VentaBoletaCambioTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @MockitoBean private VentasClient ventas;

    @Test
    void recuperaParaCambioLaBoletaEmitidaTrasReintentarLaMismaVenta() throws Exception {
        String clave = UUID.randomUUID().toString();
        var g5 = new VentasPedidoResponse("PED-2026-TEST-F9", "ENTREGADO", new BigDecimal("119.80"),
                "PEN", "RETAIL", null);
        when(ventas.crearPedido(any(), eq(clave))).thenReturn(Optional.empty(), Optional.of(g5));

        var turno = mvc.perform(post("/api/v1/retail/caja/turnos").with(jwt().jwt(t -> t.subject(VENDEDOR)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"terminalPos":"POS-F5-F9","saldoInicialEfectivo":100}
                                """))
                .andExpect(status().isCreated()).andReturn();
        var caja = json.readTree(turno.getResponse().getContentAsString());
        String cuerpo = """
                {"terminalPos":"%s","turnoId":"%s","items":[{"sku":"POLTEC-M-BLA","cantidad":2}],
                "pago":{"medioPago":"EFECTIVO","montoRecibidoEfectivo":200},"comprobante":{"tipo":"BOLETA"}}
                """.formatted(caja.path("terminalPos").asText(), caja.path("turnoId").asText());

        mvc.perform(venta(clave, cuerpo)).andExpect(status().isServiceUnavailable());
        var respuesta = mvc.perform(venta(clave, cuerpo)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.pedidoId").value(g5.pedidoId()))
                .andExpect(jsonPath("$.estadoPedido").value("ENTREGADO")).andReturn().getResponse();
        var comprobante = json.readTree(respuesta.getContentAsString()).path("comprobante");
        String boleta = "B001-" + comprobante.path("correlativo").asText();
        assertThat(boleta).matches("B001-[0-9]{8}");
        mvc.perform(venta(clave, cuerpo)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.comprobante.correlativo").value(comprobante.path("correlativo").asText()));
        mvc.perform(venta(clave, cuerpo.replace("200", "250"))).andExpect(status().isConflict());

        mvc.perform(get("/api/v1/retail/cambios/validar/" + boleta).with(jwt().jwt(t -> t.subject(VENDEDOR))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidoId").value(g5.pedidoId()))
                .andExpect(jsonPath("$.items[0].cantidadDisponibleCambio").value(2));

        mvc.perform(post("/api/v1/retail/cambios").with(jwt().jwt(t -> t.subject(VENDEDOR)))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"boletaNumero":"%s","sku":"POLTEC-M-BLA","cantidad":1,"motivo":"CAMBIO_TALLA",
                                "inspeccion":{"etiquetasIntactas":true,"sinSignosUso":true,"empaqueOriginal":true}}
                                """.formatted(boleta)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.vale.boletaOrigen").value(boleta))
                .andExpect(jsonPath("$.vale.monto").value(59.90));

        var peticiones = ArgumentCaptor.forClass(VentasCrearPedidoRequest.class);
        verify(ventas, times(2)).crearPedido(peticiones.capture(), eq(clave));
        assertThat(peticiones.getAllValues().getFirst()).isEqualTo(peticiones.getAllValues().getLast());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder venta(String clave, String cuerpo) {
        return post("/api/v1/retail/ventas").with(jwt().jwt(t -> t.subject(VENDEDOR)))
                .header("Idempotency-Key", clave).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }
}

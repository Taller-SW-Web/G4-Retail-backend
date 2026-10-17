package pe.edu.unmsm.fisi.retail.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoResponse;
import pe.edu.unmsm.fisi.retail.dto.CarritoEsperaDto;
import pe.edu.unmsm.fisi.retail.service.CarritoService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests de integración web MockMvc para {@link CarritoController} (F4).
 */
@WebMvcTest(CarritoController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("CarritoController — MockMvc Tests")
class CarritoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CarritoService carritoService;

    @Test
    @DisplayName("POST /api/v1/retail/carritos/calcular responde 200 con el balance financiero")
    void calcularTotalesResponde200() throws Exception {
        var responseMock = new CalculoCarritoResponse(
                List.of(new CalculoCarritoResponse.LineaCalculoDto(
                        "CAM-RUN-M", 2, new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("100.00"), null
                )),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                new BigDecimal("84.75"),
                new BigDecimal("15.25"),
                "PEN",
                "EVAL-12345",
                List.of()
        );

        given(carritoService.calcularTotales(any())).willReturn(responseMock);

        var request = new CalculoCarritoRequest(
                List.of(new CalculoCarritoRequest.ItemCarritoCalculoDto("CAM-RUN-M", 2, new BigDecimal("50.00"))),
                null
        );

        mockMvc.perform(post("/api/v1/retail/carritos/calcular")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalBruto").value(100.00))
                .andExpect(jsonPath("$.totalPagar").value(100.00))
                .andExpect(jsonPath("$.baseImponible").value(84.75))
                .andExpect(jsonPath("$.montoIgv").value(15.25))
                .andExpect(jsonPath("$.moneda").value("PEN"));
    }

    @Test
    @DisplayName("POST /api/v1/retail/carritos-espera responde 201 Created con identificador y estado SUSPENDIDO")
    void pausarCarritoResponde201() throws Exception {
        UUID id = UUID.randomUUID();
        var responseMock = new CarritoEsperaDto(
                id,
                "Probador 2",
                null,
                List.of(new CarritoEsperaDto.ItemCarritoEsperaDto(
                        "SKU-01", "P-1", "Polo", "M / Azul", 1, new BigDecimal("50.00")
                )),
                new BigDecimal("50.00"),
                OffsetDateTime.now(),
                OffsetDateTime.now().plusHours(2),
                "SUSPENDIDO"
        );

        given(carritoService.guardarCarritoEspera(any())).willReturn(responseMock);

        var request = new CarritoEsperaDto(
                null,
                "Probador 2",
                null,
                List.of(new CarritoEsperaDto.ItemCarritoEsperaDto(
                        "SKU-01", "P-1", "Polo", "M / Azul", 1, new BigDecimal("50.00")
                )),
                null, null, null, null
        );

        mockMvc.perform(post("/api/v1/retail/carritos-espera")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.carritoEsperaId").value(id.toString()))
                .andExpect(jsonPath("$.aliasTicket").value("Probador 2"))
                .andExpect(jsonPath("$.estado").value("SUSPENDIDO"));
    }

    @Test
    @DisplayName("GET /api/v1/retail/carritos-espera responde 200 con la lista")
    void listarCarritosEsperaResponde200() throws Exception {
        given(carritoService.listarCarritosEspera("SUSPENDIDO")).willReturn(List.of());

        mockMvc.perform(get("/api/v1/retail/carritos-espera?estado=SUSPENDIDO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}

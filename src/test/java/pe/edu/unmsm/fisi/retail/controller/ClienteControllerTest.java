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

import pe.edu.unmsm.fisi.retail.dto.ClienteAltaRapidaRequest;
import pe.edu.unmsm.fisi.retail.dto.ClienteResponse;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.service.ClienteService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests de integracion web MockMvc para {@link ClienteController} (F3).
 */
@WebMvcTest(ClienteController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ClienteController — MockMvc Tests")
class ClienteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ClienteService clienteService;

    @Test
    @DisplayName("GET /api/v1/retail/clientes responde 200 con los datos de identificacion")
    void buscarPorDocumentoResponde200() throws Exception {
        var responseMock = new ClienteResponse("cli-001", "DNI", "12345678", "Ana Quispe");
        given(clienteService.buscarPorDocumento("DNI", "12345678")).willReturn(responseMock);

        mockMvc.perform(get("/api/v1/retail/clientes")
                        .param("tipoDocumento", "DNI")
                        .param("numeroDocumento", "12345678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clienteId").value("cli-001"))
                .andExpect(jsonPath("$.tipoDocumento").value("DNI"))
                .andExpect(jsonPath("$.numeroDocumento").value("12345678"))
                .andExpect(jsonPath("$.nombreCompleto").value("Ana Quispe"));
    }

    @Test
    @DisplayName("GET /api/v1/retail/clientes responde 404 cuando el documento no figura en el padron")
    void buscarPorDocumentoInexistenteResponde404() throws Exception {
        given(clienteService.buscarPorDocumento(anyString(), anyString()))
                .willThrow(new RecursoNoEncontradoException("CLIENTE_NO_ENCONTRADO",
                        "El documento 00000000 no figura en el padron de la tienda"));

        mockMvc.perform(get("/api/v1/retail/clientes")
                        .param("tipoDocumento", "DNI")
                        .param("numeroDocumento", "00000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLIENTE_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("GET /api/v1/retail/clientes responde 400 cuando falta un parametro obligatorio")
    void buscarPorDocumentoSinParametrosResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/retail/clientes")
                        .param("tipoDocumento", "DNI"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_OBLIGATORIO"));
    }

    @Test
    @DisplayName("POST /api/v1/retail/clientes responde 201 con el cliente registrado")
    void registrarAltaRapidaResponde201() throws Exception {
        var responseMock = new ClienteResponse("cli-002", "DNI", "45678912", "Pedro Soto");
        given(clienteService.registrarAltaRapida(any())).willReturn(responseMock);

        var request = new ClienteAltaRapidaRequest(
                "RETAIL", "DNI", "45678912", "Pedro", "Soto", "pedro@correo.com", "987654321");

        mockMvc.perform(post("/api/v1/retail/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clienteId").value("cli-002"))
                .andExpect(jsonPath("$.tipoDocumento").value("DNI"))
                .andExpect(jsonPath("$.numeroDocumento").value("45678912"))
                .andExpect(jsonPath("$.nombreCompleto").value("Pedro Soto"));
    }

    @Test
    @DisplayName("POST /api/v1/retail/clientes responde 400 cuando el documento no tiene 8 digitos")
    void registrarAltaRapidaConDocumentoInvalidoResponde400() throws Exception {
        var request = new ClienteAltaRapidaRequest(
                "RETAIL", "DNI", "1234567", "Pedro", "Soto", "pedro@correo.com", "987654321");

        mockMvc.perform(post("/api/v1/retail/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(clienteService, never()).registrarAltaRapida(any());
    }

    @Test
    @DisplayName("POST /api/v1/retail/clientes responde 409 cuando el documento ya esta registrado")
    void registrarAltaRapidaDuplicadaResponde409() throws Exception {
        given(clienteService.registrarAltaRapida(any()))
                .willThrow(new ConflictoException("CLIENTE_YA_REGISTRADO",
                        "El documento 12345678 ya esta registrado en el padron"));

        var request = new ClienteAltaRapidaRequest(
                "RETAIL", "DNI", "12345678", "Ana", "Quispe", "ana@correo.com", "987654321");

        mockMvc.perform(post("/api/v1/retail/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENTE_YA_REGISTRADO"));
    }
}

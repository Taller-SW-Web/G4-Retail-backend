package pe.edu.unmsm.fisi.retail.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.edu.unmsm.fisi.retail.dto.ClienteAltaRapidaRequest;
import pe.edu.unmsm.fisi.retail.dto.ClienteResponse;
import pe.edu.unmsm.fisi.retail.service.ClienteService;

/**
 * Identificacion y alta rapida de clientes en mostrador (F3, RF-07 / RF-08 / RF-09).
 *
 * <pre>
 * GET  /api/v1/retail/clientes?tipoDocumento={tipo}&numeroDocumento={numero}
 * POST /api/v1/retail/clientes
 * </pre>
 *
 * <p><strong>Limite estricto:</strong> solo maneja datos de identificacion (documento, nombre,
 * apellido). NO procesa ventas ni cobra dinero (eso corresponde a F4 y F5).</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/retail")
@RequiredArgsConstructor
public class ClienteController {

    private final ClienteService clienteService;

    /**
     * Busca al cliente en el padron por tipo y numero de documento. Responde {@code 404} cuando el
     * documento no figura, que es la senal que usa el mostrador para ofrecer el alta rapida (RF-08).
     */
    @GetMapping("/clientes")
    public ResponseEntity<ClienteResponse> buscarPorDocumento(
            @RequestParam("tipoDocumento") String tipoDocumento,
            @RequestParam("numeroDocumento") String numeroDocumento) {
        return ResponseEntity.ok(clienteService.buscarPorDocumento(tipoDocumento, numeroDocumento));
    }

    /**
     * Registra un cliente nuevo capturado en mostrador. Responde {@code 409} si el documento ya
     * existe, para ofrecer vincular al cliente que ya estaba.
     */
    @PostMapping("/clientes")
    public ResponseEntity<ClienteResponse> registrarAltaRapida(
            @Valid @RequestBody ClienteAltaRapidaRequest request) {
        ClienteResponse registrado = clienteService.registrarAltaRapida(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(registrado);
    }
}

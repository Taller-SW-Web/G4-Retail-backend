package pe.edu.unmsm.fisi.retail.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
import pe.edu.unmsm.fisi.retail.service.VentaService;

/**
 * Endpoint transaccional de cierre de venta presencial en mostrador (F5).
 *
 * <pre>
 * POST /api/v1/retail/ventas
 * </pre>
 *
 * <p><strong>Alcance (RF-13, RF-14, RF-15):</strong></p>
 * <ul>
 *   <li>RF-13: Procesar el pago en tienda (efectivo o tarjeta POS).</li>
 *   <li>RF-14: Determinar el tipo de comprobante (boleta o factura) y calcular el vuelto.</li>
 *   <li>RF-15: Emitir la boleta B001 con correlativo incremental y QR de verificacion.</li>
 * </ul>
 *
 * <p><strong>Limites estrictos — esto NO hace este controller:</strong></p>
 * <ul>
 *   <li>No valida documentos de identidad del cliente — eso es F3 {@code ClienteController}.</li>
 *   <li>No abre ni cierra turnos de caja — eso es F8 {@code CajaController}.</li>
 *   <li>No descuenta stock — eso es F2 {@code CatalogoController}.</li>
 * </ul>
 *
 * <p>El header {@code Idempotency-Key} es <strong>obligatorio</strong>: garantiza que si la red
 * reintenta la peticion no se cree una segunda venta duplicada (decision D-06 del contrato v0.4).</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/retail/ventas")
@RequiredArgsConstructor
public class VentaController {

    private final VentaService ventaService;

    /**
     * Registra la venta, emite el comprobante y devuelve los datos para el ticket termico.
     *
     * @param idempotencyKey clave unica generada por el cliente (UUID recomendado)
     * @param request        cuerpo de la venta con items, pago y comprobante
     * @return {@code 201 Created} con el pedidoId, comprobante y ticket de regalo (si aplica)
     */
    @PostMapping
    public ResponseEntity<VentaResponse> registrarVenta(
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "El header Idempotency-Key es obligatorio")
            String idempotencyKey,

            @Valid @RequestBody VentaRequest request
    ) {
        VentaResponse respuesta = ventaService.registrarVenta(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);
    }
}

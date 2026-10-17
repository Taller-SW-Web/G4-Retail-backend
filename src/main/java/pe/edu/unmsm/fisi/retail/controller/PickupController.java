package pe.edu.unmsm.fisi.retail.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupRequest;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.service.PickupService;

import java.util.List;
import java.util.UUID;

/**
 * Retiro en tienda / Pickup (F7): RF-18 y RF-19.
 *
 * <p>Las rutas son las que fija el documento de reparto de la Semana 8:</p>
 * <ul>
 *   <li>{@code GET /api/v1/retail/pedidos/pickup} — bandeja de pedidos para recojo (RF-18)</li>
 *   <li>{@code POST /api/v1/retail/pedidos/{pedidoId}/entrega} — confirmación de entrega física (RF-19)</li>
 * </ul>
 *
 * <p>Ambos endpoints exigen token válido. El encargado de la entrega no se recibe del cliente: es el
 * {@code sub} del JWT.</p>
 */
@RestController
@RequestMapping("/api/v1/retail/pedidos")
@RequiredArgsConstructor
public class PickupController {

    private final PickupService pickupService;

    /** Pedidos pickup de la tienda pendientes de entrega (RF-18). */
    @GetMapping("/pickup")
    public List<PedidoPickupResponse> pendientes() {
        return pickupService.pendientes();
    }

    /** Registra la entrega física y devuelve la constancia (RF-19). */
    @PostMapping("/{pedidoId}/entrega")
    public EntregaPickupResponse entregar(@AuthenticationPrincipal Jwt token,
                                          @PathVariable String pedidoId,
                                          @Valid @RequestBody EntregaPickupRequest peticion) {
        return pickupService.entregar(pedidoId, usuarioDelToken(token), peticion);
    }

    /** Extrae el {@code sub} del token; misma resolución que {@code CajaController}, sin acoplar F7 a F8. */
    private UUID usuarioDelToken(Jwt token) {
        try {
            return UUID.fromString(token.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccesoDenegadoException("TOKEN_SIN_USUARIO_VALIDO",
                    "El token no identifica a un usuario valido");
        }
    }
}

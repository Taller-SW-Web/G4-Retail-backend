package pe.edu.unmsm.fisi.retail.controller;

import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unmsm.fisi.retail.dto.ControlDtos.CampanaVigenteResponse;
import pe.edu.unmsm.fisi.retail.dto.ControlDtos.ResumenControlResponse;
import pe.edu.unmsm.fisi.retail.dto.ControlDtos.VarianteCriticaResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.service.ControlService;

import java.util.List;
import java.util.UUID;

/**
 * Torre de control operativa del turno (F11): RF-29, RF-30 y RF-31.
 *
 * <p>Rutas del contrato v0.5, §5.11. Todas toman la tienda del perfil del vendedor autenticado.</p>
 */
@RestController
@RequestMapping("/api/v1/retail/control")
@RequiredArgsConstructor
@Validated
public class ControlController {

    private final ControlService controlService;

    /** Contadores del badge de la campana; la SPA lo consulta cada 60 s. */
    @GetMapping("/resumen")
    public ResumenControlResponse resumen(@AuthenticationPrincipal Jwt token) {
        return controlService.resumen(usuarioDelToken(token));
    }

    /** RF-30: pickups listos para recojo en la tienda, opcionalmente de un solo titular. */
    @GetMapping("/pickup-pendientes")
    public List<PedidoPickupResponse> pickupPendientes(
            @AuthenticationPrincipal Jwt token,
            @RequestParam(required = false) @Pattern(regexp = "^\\d{8}$", message = "Debe tener 8 dígitos") String dni) {
        return controlService.pickupPendientes(usuarioDelToken(token), dni);
    }

    /** RF-29: variantes agotadas o críticas en la tienda. */
    @GetMapping("/stock-critico")
    public List<VarianteCriticaResponse> stockCritico(@AuthenticationPrincipal Jwt token) {
        return controlService.stockCritico(usuarioDelToken(token));
    }

    /** RF-31: campañas vigentes del canal RETAIL. */
    @GetMapping("/campanas-vigentes")
    public List<CampanaVigenteResponse> campanasVigentes(@AuthenticationPrincipal Jwt token) {
        return controlService.campanasVigentes(usuarioDelToken(token));
    }

    private UUID usuarioDelToken(Jwt token) {
        try {
            return UUID.fromString(token.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccesoDenegadoException("TOKEN_SIN_USUARIO_VALIDO",
                    "El token no identifica a un usuario valido");
        }
    }
}

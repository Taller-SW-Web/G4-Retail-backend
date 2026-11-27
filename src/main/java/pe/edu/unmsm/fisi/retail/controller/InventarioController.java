package pe.edu.unmsm.fisi.retail.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unmsm.fisi.retail.dto.CuarentenaItemResponse;
import pe.edu.unmsm.fisi.retail.dto.IncidenciaResponse;
import pe.edu.unmsm.fisi.retail.dto.RegistroIncidenciaRequest;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.service.CuarentenaService;
import pe.edu.unmsm.fisi.retail.service.IncidenciaInventarioService;

import java.util.List;
import java.util.UUID;

/**
 * Incidencias de inventario y cuarentena en tienda (F12, contrato §5.12).
 *
 * <ul>
 *   <li>{@code POST /api/v1/retail/inventario/incidencias}: reporta una prenda dañada o no ubicada (RF-32).</li>
 *   <li>{@code GET /api/v1/retail/inventario/cuarentena}: bandeja de la tienda (RF-33).</li>
 * </ul>
 *
 * <p>El acta de merma ({@code POST /inventario/actas-merma}, RF-34) va en su propio controlador.</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/retail/inventario")
@RequiredArgsConstructor
public class InventarioController {

    private final IncidenciaInventarioService incidenciaService;
    private final CuarentenaService cuarentenaService;

    /**
     * Registra la incidencia. Responde {@code 201} con la incidencia en {@code PENDIENTE_REPORTE}: la
     * prenda queda bloqueada en el POS desde ese momento.
     *
     * <p>Errores: {@code 400} por datos o cabecera faltantes; {@code 403} sin perfil;
     * {@code 409 TURNO_NO_ABIERTO}; {@code 409 IDEMPOTENCY_CONFLICT}.</p>
     */
    @PostMapping("/incidencias")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidenciaResponse registrar(
            @AuthenticationPrincipal Jwt token,
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "La cabecera Idempotency-Key no puede estar vacia")
            @Size(max = 64, message = "La cabecera Idempotency-Key admite hasta 64 caracteres")
            String idempotencyKey,
            @Valid @RequestBody RegistroIncidenciaRequest peticion) {
        return incidenciaService.registrar(usuarioDelToken(token), idempotencyKey, peticion);
    }

    /** Prendas en cuarentena activa de la tienda del vendedor. */
    @GetMapping("/cuarentena")
    public List<CuarentenaItemResponse> cuarentena(@AuthenticationPrincipal Jwt token) {
        return cuarentenaService.listar(usuarioDelToken(token));
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

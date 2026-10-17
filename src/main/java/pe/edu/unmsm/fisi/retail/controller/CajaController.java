package pe.edu.unmsm.fisi.retail.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unmsm.fisi.retail.dto.AperturaTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.CierreTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.MovimientoTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.MovimientoTurnoResponse;
import pe.edu.unmsm.fisi.retail.dto.ReporteZResponse;
import pe.edu.unmsm.fisi.retail.dto.TurnoCajaResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.service.CajaService;

import java.util.UUID;

/**
 * Control de turno y cuadre de caja (F8): RF-20, RF-21 y RF-22.
 *
 * <p>Las rutas son las que fija el documento de reparto de la Semana 8:</p>
 * <ul>
 *   <li>{@code POST /api/v1/retail/caja/turnos} — apertura con fondo fijo (RF-20)</li>
 *   <li>{@code GET /api/v1/retail/caja/turnos/actual} — consulta del turno vigente</li>
 *   <li>{@code POST /api/v1/retail/caja/turnos/{turnoId}/movimientos} — caja chica (RF-21)</li>
 *   <li>{@code POST /api/v1/retail/caja/turnos/{turnoId}/cierre} — arqueo ciego (RF-22)</li>
 * </ul>
 *
 * <p>El turno viaja como subrecurso ({@code {turnoId}}), de modo que un movimiento o un cierre no
 * pueden aplicarse a un turno distinto del que el cajero tiene delante.</p>
 *
 * <p>Todos los endpoints exigen token válido: operar efectivo es la operación más sensible del
 * mostrador, así que ninguno es público (la única ruta pública es {@code /health}). La tienda no se
 * recibe del cliente: se resuelve desde el {@code sub} del JWT contra {@code RET_PERSONAL_TIENDA}.</p>
 */
@RestController
@RequestMapping("/api/v1/retail/caja/turnos")
@RequiredArgsConstructor
public class CajaController {

    private final CajaService cajaService;

    /**
     * Turno vigente de la tienda (RF-20).
     *
     * <p>La SPA lo consulta al entrar al mostrador para decidir si debe exigir la apertura. Sin
     * turno abierto responde {@code 200} con {@code estado = CERRADA}: no es un error, es la
     * respuesta normal de una terminal que todavía no abrió.</p>
     */
    @GetMapping("/actual")
    public TurnoCajaResponse turnoActual(@AuthenticationPrincipal Jwt token) {
        return cajaService.turnoActual(usuarioDelToken(token));
    }

    /** Abre el turno con fondo fijo (RF-20). {@code 409} si la terminal ya tiene uno abierto. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TurnoCajaResponse abrir(@AuthenticationPrincipal Jwt token,
                                   @Valid @RequestBody AperturaTurnoRequest peticion) {
        return cajaService.abrir(usuarioDelToken(token), peticion);
    }

    /** Registra un ingreso o egreso menor del turno (RF-21). {@code 422} si el egreso no cabe. */
    @PostMapping("/{turnoId}/movimientos")
    @ResponseStatus(HttpStatus.CREATED)
    public MovimientoTurnoResponse registrarMovimiento(@AuthenticationPrincipal Jwt token,
                                                       @PathVariable UUID turnoId,
                                                       @Valid @RequestBody MovimientoTurnoRequest peticion) {
        return cajaService.registrarMovimiento(usuarioDelToken(token), turnoId, peticion);
    }

    /** Cierra el turno con arqueo ciego y devuelve el Reporte Z (RF-22). */
    @PostMapping("/{turnoId}/cierre")
    public ReporteZResponse cerrar(@AuthenticationPrincipal Jwt token,
                                   @PathVariable UUID turnoId,
                                   @Valid @RequestBody CierreTurnoRequest peticion) {
        return cajaService.cerrar(usuarioDelToken(token), turnoId, peticion);
    }

    /**
     * Extrae el {@code sub} del token, que identifica a la persona en Seguridad (G7).
     *
     * <p>Si no es un UUID, el token está mal formado: se rechaza en lugar de intentar adivinar de
     * quién se trata. Es la misma resolución que usa {@code AuthController}; se repite aquí para no
     * acoplar F8 a F1.</p>
     */
    private UUID usuarioDelToken(Jwt token) {
        try {
            return UUID.fromString(token.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccesoDenegadoException("TOKEN_SIN_USUARIO_VALIDO",
                    "El token no identifica a un usuario valido");
        }
    }
}

package pe.edu.unmsm.fisi.retail.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unmsm.fisi.retail.dto.ActaEmitidaResponse;
import pe.edu.unmsm.fisi.retail.dto.EmisionActaRequest;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.service.ActaDiscrepanciaService;

import java.util.UUID;

/**
 * Acta de merma de tienda (F12, RF-34, contrato 5.12). Solo SUPERVISOR.
 *
 * <p>Va en su propio controlador para no cruzarse con los endpoints de incidencias y cuarentena de F12.</p>
 */
@RestController
@RequestMapping("/api/v1/retail/inventario/actas-merma")
@RequiredArgsConstructor
public class ActaMermaController {

    private final ActaDiscrepanciaService actas;

    /**
     * Emite el acta y saca de la cuarentena las prendas incluidas.
     *
     * <p>Errores: {@code 400 LISTA_VACIA}; {@code 403 ROL_NO_PERMITIDO} si no es supervisor;
     * {@code 409 INCIDENCIA_NO_EN_CUARENTENA} si alguna prenda no esta {@code EN_CUARENTENA} en su tienda.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ActaEmitidaResponse emitir(@AuthenticationPrincipal Jwt token, @Valid @RequestBody EmisionActaRequest peticion) {
        return actas.emitir(usuarioDelToken(token), peticion);
    }

    private UUID usuarioDelToken(Jwt token) {
        try {
            return UUID.fromString(token.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccesoDenegadoException("TOKEN_SIN_USUARIO_VALIDO", "El token no identifica a un usuario valido");
        }
    }
}

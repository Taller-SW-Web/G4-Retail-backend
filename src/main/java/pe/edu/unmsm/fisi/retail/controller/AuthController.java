package pe.edu.unmsm.fisi.retail.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unmsm.fisi.retail.dto.PerfilResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.service.AuthService;

import java.util.UUID;

/**
 * Sesion del vendedor en mostrador (F1).
 *
 * <p>Retail <strong>no expone un login</strong>: el token lo emite Seguridad (G7) y la SPA le habla
 * directo. Aqui solo se responde quien es el usuario autenticado dentro de esta tienda, que es lo
 * que Seguridad no sabe.</p>
 */
@RestController
@RequestMapping("/api/v1/retail")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Perfil operativo del usuario autenticado. La SPA lo llama justo despues del login.
     *
     * <p>Contrato: seccion 5.1. Responde {@code 401} si el token no es valido, lo resuelve el filtro
     * de seguridad, y {@code 403 FORBIDDEN_USUARIO_SIN_TIENDA} si la persona existe en Seguridad
     * pero no esta dada de alta como personal de la tienda.</p>
     */
    @GetMapping("/perfil")
    public PerfilResponse obtenerPerfil(@AuthenticationPrincipal Jwt token) {
        return authService.obtenerPerfil(usuarioDelToken(token));
    }

    /**
     * Extrae el {@code sub} del token, que identifica a la persona en Seguridad.
     *
     * <p>Si no es un UUID, el token esta mal formado: se rechaza en lugar de intentar adivinar de
     * quien se trata.</p>
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

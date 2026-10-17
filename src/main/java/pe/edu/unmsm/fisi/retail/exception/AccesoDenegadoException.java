package pe.edu.unmsm.fisi.retail.exception;

import lombok.Getter;

/**
 * El usuario esta autenticado, pero no tiene permiso para esta operacion. Se traduce a {@code 403}.
 *
 * <p>No confundir con {@code 401}: ahi el problema es el token, aqui el token es valido y el
 * problema es el perfil. El caso tipico es {@code FORBIDDEN_USUARIO_SIN_TIENDA}, cuando alguien de
 * Seguridad existe pero no esta dado de alta como personal de la tienda.</p>
 */
@Getter
public class AccesoDenegadoException extends RuntimeException {

    /** Codigo estable que consume el cliente, en UPPER_SNAKE_CASE (seccion 2.5 del contrato). */
    private final String codigo;

    public AccesoDenegadoException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }
}

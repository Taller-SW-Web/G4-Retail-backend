package pe.edu.unmsm.fisi.retail.exception;

import lombok.Getter;

/**
 * Parámetro de consulta ausente o con formato inválido (contrato §2.6): {@code 400} con
 * {@code PARAMETRO_OBLIGATORIO} o {@code PARAMETRO_INVALIDO} y el campo en {@code errores[]}.
 */
@Getter
public class ParametroInvalidoException extends RuntimeException {

    private final String codigo;
    private final String campo;

    public ParametroInvalidoException(String codigo, String campo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
        this.campo = campo;
    }
}

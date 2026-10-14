package pe.edu.unmsm.fisi.retail.exception;

import lombok.Getter;

/**
 * El recurso solicitado no existe. Se traduce a {@code 404} con el codigo de negocio indicado,
 * por ejemplo {@code CODIGO_BARRAS_NO_ENCONTRADO}.
 */
@Getter
public class RecursoNoEncontradoException extends RuntimeException {

    /** Codigo estable que consume el cliente, en UPPER_SNAKE_CASE (seccion 2.5 del contrato). */
    private final String codigo;

    public RecursoNoEncontradoException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }
}

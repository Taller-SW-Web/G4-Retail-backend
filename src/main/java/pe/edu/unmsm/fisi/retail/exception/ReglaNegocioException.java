package pe.edu.unmsm.fisi.retail.exception;

import lombok.Getter;

/**
 * La peticion es valida en forma, pero una regla de negocio la rechaza. Se traduce a {@code 422},
 * por ejemplo cuando se intenta vender una variante en cuarentena.
 */
@Getter
public class ReglaNegocioException extends RuntimeException {

    /** Codigo estable que consume el cliente, en UPPER_SNAKE_CASE (seccion 2.5 del contrato). */
    private final String codigo;

    public ReglaNegocioException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }
}

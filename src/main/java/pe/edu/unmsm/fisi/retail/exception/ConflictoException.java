package pe.edu.unmsm.fisi.retail.exception;

import lombok.Getter;

/**
 * La peticion choca con el estado actual del recurso: se traduce a {@code 409}.
 *
 * <p>El caso tipico de F8 es abrir un segundo turno donde ya hay uno abierto
 * ({@code CAJA_YA_ABIERTA}), que el contrato pide responder como {@code 409 Conflict} y no como un
 * {@code 422}: la peticion es correcta, el problema es el momento en que llega.</p>
 *
 * <p>No confundir con {@link ReglaNegocioException} ({@code 422}): ahi la peticion esta mal para
 * cualquier estado del sistema, por ejemplo girar mas efectivo del que hay en gaveta.</p>
 */
@Getter
public class ConflictoException extends RuntimeException {

    /** Codigo estable que consume el cliente, en UPPER_SNAKE_CASE (seccion 2.5 del contrato). */
    private final String codigo;

    public ConflictoException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }
}

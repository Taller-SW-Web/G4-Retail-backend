package pe.edu.unmsm.fisi.retail.exception;

import lombok.Getter;

/**
 * Un módulo externo (Ventas G5, Seguridad G7) no respondió a tiempo o falló: se traduce a
 * {@code 503}.
 *
 * <p>La petición del vendedor es válida; el problema es la dependencia. El cliente puede reintentar
 * sin cambiar nada.</p>
 */
@Getter
public class ServicioExternoNoDisponibleException extends RuntimeException {

    /** Código estable que consume el cliente, en UPPER_SNAKE_CASE (sección 2.5 del contrato). */
    private final String codigo;

    public ServicioExternoNoDisponibleException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }
}

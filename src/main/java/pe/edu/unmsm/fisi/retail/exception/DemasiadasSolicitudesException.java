package pe.edu.unmsm.fisi.retail.exception;

import lombok.Getter;

/**
 * Límite de consultas excedido: {@code 429} con {@code Retry-After} (contrato v0.5, §2.5 y §2.8).
 *
 * <p>Se usa cuando otro módulo responde {@code 429}, por ejemplo las 60 búsquedas por hora y vendedor
 * de Seguridad (G7): Retail conserva su {@code Retry-After}.</p>
 */
@Getter
public class DemasiadasSolicitudesException extends RuntimeException {

    public static final String CODIGO = "DEMASIADAS_SOLICITUDES";

    private final long reintentarEnSegundos;

    public DemasiadasSolicitudesException(String mensaje, long reintentarEnSegundos) {
        super(mensaje);
        this.reintentarEnSegundos = reintentarEnSegundos;
    }
}

package pe.edu.unmsm.fisi.retail.service.outbox;

/**
 * El destino no aceptó un evento del outbox.
 *
 * <p>{@link #esDefinitivo()} separa los dos casos de §2.9: un rechazo definitivo ({@code 400},
 * {@code 401}, {@code 403}, {@code 404}, {@code 409}, {@code 422}) pasa el evento a {@code FALLIDO};
 * un error transitorio (sin respuesta, timeout, {@code 408}, {@code 429}, {@code 5xx}) se reintenta.</p>
 */
public class EnvioOutboxException extends RuntimeException {

    private final boolean definitivo;

    private EnvioOutboxException(String mensaje, boolean definitivo) {
        super(mensaje);
        this.definitivo = definitivo;
    }

    public static EnvioOutboxException transitorio(String mensaje) {
        return new EnvioOutboxException(mensaje, false);
    }

    public static EnvioOutboxException definitivo(String mensaje) {
        return new EnvioOutboxException(mensaje, true);
    }

    /** Clasifica un código HTTP de respuesta según §2.9. */
    public static EnvioOutboxException porEstadoHttp(int estado, String mensaje) {
        boolean transitorio = estado == 408 || estado == 429 || estado >= 500;
        return new EnvioOutboxException("HTTP " + estado + ": " + mensaje, !transitorio);
    }

    public boolean esDefinitivo() {
        return definitivo;
    }
}

package pe.edu.unmsm.fisi.retail.config.gestionapi;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * {@code X-Correlation-Id} de la petición en curso (contrato v0.5, §2.3 y §2.8).
 *
 * <p>{@link TrazabilidadFilter} lo fija al entrar la petición: lo deja en el MDC (para los logs y las
 * llamadas salientes) y como atributo de la petición (para el cuerpo de los errores). Así la
 * respuesta, los logs y los demás módulos ven el mismo valor.</p>
 */
public final class Correlacion {

    public static final String ENCABEZADO = "X-Correlation-Id";
    /** Clave en el MDC; la misma que usa el outbox para guardar el origen del evento. */
    public static final String MDC_CLAVE = "correlationId";
    static final String ATRIBUTO = Correlacion.class.getName();

    private Correlacion() {
    }

    /** Valor de la petición: el fijado por el filtro, el que llegó en la cabecera o uno nuevo. */
    public static String de(HttpServletRequest peticion) {
        Object fijado = peticion.getAttribute(ATRIBUTO);
        if (fijado instanceof String valor && !valor.isBlank()) {
            return valor;
        }
        String recibido = peticion.getHeader(ENCABEZADO);
        return (recibido != null && TrazabilidadFilter.ID_VALIDO.matcher(recibido).matches())
                ? recibido : UUID.randomUUID().toString();
    }

    /** Valor del hilo actual, o {@code null} si la llamada no nació de una petición HTTP. */
    public static String actual() {
        return MDC.get(MDC_CLAVE);
    }
}

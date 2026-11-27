package pe.edu.unmsm.fisi.retail.config.gestionapi;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Política de trazabilidad y versionado del API Management de Retail (contrato v0.5, §2.8).
 *
 * <ul>
 *   <li>Acepta el {@code X-Correlation-Id} de la petición si es un UUID (G6 rechaza con {@code 400} los que
 *   no lo son) o genera uno, y lo devuelve en la respuesta.</li>
 *   <li>Lo deja en el MDC: aparece en cada línea de log y {@link CorrelacionSaliente} lo propaga a G5,
 *   G6 y G7.</li>
 *   <li>Marca cada respuesta con {@code X-Api-Version: v1}.</li>
 * </ul>
 *
 * <p>Corre antes que la cadena de seguridad, para que también los {@code 401} lleven la cabecera.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrazabilidadFilter extends OncePerRequestFilter {

    public static final String ENCABEZADO_VERSION = "X-Api-Version";
    public static final String VERSION_ACTUAL = "v1";

    /** Un id recibido solo se reutiliza si es un UUID; si no, se genera otro (contrato v0.5.2, §2.8). */
    static final Pattern ID_VALIDO = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String recibido = peticion.getHeader(Correlacion.ENCABEZADO);
        String id = recibido != null && ID_VALIDO.matcher(recibido).matches()
                ? recibido : UUID.randomUUID().toString();

        peticion.setAttribute(Correlacion.ATRIBUTO, id);
        respuesta.setHeader(Correlacion.ENCABEZADO, id);
        respuesta.setHeader(ENCABEZADO_VERSION, VERSION_ACTUAL);
        MDC.put(Correlacion.MDC_CLAVE, id);
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            MDC.remove(Correlacion.MDC_CLAVE);
        }
    }
}

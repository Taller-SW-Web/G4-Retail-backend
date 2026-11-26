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
 *   <li>Acepta el {@code X-Correlation-Id} de la petición o genera uno, y lo devuelve en la respuesta.</li>
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

    /** Un id recibido solo se reutiliza si es razonable; si no, se genera otro (evita inyectar logs). */
    private static final Pattern ID_VALIDO = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

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

package pe.edu.unmsm.fisi.retail.config.gestionapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Política de límite de peticiones del API Management de Retail (contrato v0.5, §2.8).
 *
 * <p>Corre después de la cadena de seguridad, así que ya conoce al vendedor autenticado. Cuenta por
 * vendedor ({@code sub} del JWT) o por IP en las rutas públicas. Toda respuesta lleva
 * {@code X-RateLimit-Limit} y {@code X-RateLimit-Remaining}. Al pasar el límite responde
 * {@code 429 DEMASIADAS_SOLICITUDES} en {@code problem+json} con {@code Retry-After}.</p>
 *
 * <p>No cuentan el heartbeat ({@code /health}, que la SPA consulta seguido para detectar la
 * contingencia), la documentación OpenAPI ni la consola H2.</p>
 */
@Slf4j
@Component
public class LimitePeticionesFilter extends OncePerRequestFilter {

    public static final String CODIGO = "DEMASIADAS_SOLICITUDES";
    private static final List<String> RUTAS_EXENTAS = List.of(
            "/api/v1/retail/health", "/v3/api-docs", "/swagger-ui", "/h2-console");

    private final LimitadorPeticiones limitador;
    private final boolean habilitado;
    private final ObjectMapper mapper;

    public LimitePeticionesFilter(
            @Value("${retail.api.limite.habilitado:true}") boolean habilitado,
            @Value("${retail.api.limite.peticiones-por-minuto:120}") int porMinuto,
            ObjectProvider<Clock> reloj,
            ObjectMapper mapper) {
        this.habilitado = habilitado;
        this.limitador = new LimitadorPeticiones(porMinuto, reloj.getIfAvailable(Clock::systemUTC));
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest peticion) {
        String ruta = peticion.getRequestURI();
        return !habilitado || RUTAS_EXENTAS.stream().anyMatch(ruta::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String cliente = cliente(peticion);
        LimitadorPeticiones.Resultado resultado = limitador.consumir(cliente);
        respuesta.setHeader("X-RateLimit-Limit", String.valueOf(resultado.limite()));
        respuesta.setHeader("X-RateLimit-Remaining", String.valueOf(resultado.restantes()));

        if (resultado.permitido()) {
            cadena.doFilter(peticion, respuesta);
            return;
        }
        log.warn("Límite de peticiones excedido: cliente={} ruta={} {}", cliente, peticion.getMethod(),
                peticion.getRequestURI());
        respuesta.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(resultado.reintentarEnSegundos()));
        escribir429(peticion, respuesta, resultado);
    }

    /** Vendedor autenticado o, si no hay token, su IP. */
    private static String cliente(HttpServletRequest peticion) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
            return "usuario:" + jwt.getSubject();
        }
        String reenviada = peticion.getHeader("X-Forwarded-For");
        String ip = reenviada != null && !reenviada.isBlank() ? reenviada.split(",")[0].trim() : peticion.getRemoteAddr();
        return "ip:" + ip;
    }

    private void escribir429(HttpServletRequest peticion, HttpServletResponse respuesta,
                             LimitadorPeticiones.Resultado resultado) throws IOException {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Se superó el límite de " + resultado.limite() + " peticiones por minuto. Reintente en "
                        + resultado.reintentarEnSegundos() + " s");
        problema.setType(URI.create("https://retail.g4/problemas/demasiadas-solicitudes"));
        problema.setTitle("Demasiadas solicitudes");
        problema.setInstance(URI.create(peticion.getRequestURI()));
        problema.setProperty("code", CODIGO);
        problema.setProperty("correlationId", Correlacion.de(peticion));
        problema.setProperty("timestamp", OffsetDateTime.now().toString());

        respuesta.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(respuesta.getOutputStream(), problema);
    }

    /** Cada 5 minutos se descartan las ventanas ya cerradas. */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 300_000L)
    public void limpiar() {
        limitador.limpiarVencidas();
    }
}

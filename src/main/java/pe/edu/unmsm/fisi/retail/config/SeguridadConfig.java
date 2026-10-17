package pe.edu.unmsm.fisi.retail.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Seguridad de la API: validacion local del JWT que emite Seguridad (G7).
 *
 * <p>Retail <strong>no emite tokens ni valida contrasenias</strong>: eso es exclusivo de Seguridad.
 * Aqui solo se comprueba que el token sea legitimo y se usa su {@code sub} para resolver el perfil
 * operativo en {@code RET_PERSONAL_TIENDA} (seccion 2.4 del contrato v0.4).</p>
 *
 * <p>Durante la Semana 8, el token lo simula el frontend con los vendedores semilla, firmado con la
 * clave compartida de desarrollo. Cambiar al Seguridad real es reemplazar el decodificador por uno
 * que lea el JWKS publicado, sin tocar controladores ni servicios.</p>
 */
@Configuration
@EnableWebSecurity
public class SeguridadConfig {

    /** Rutas publicas: el heartbeat de contingencia y la consola de la base en desarrollo. */
    private static final String[] RUTAS_PUBLICAS = {"/api/v1/retail/health", "/h2-console/**"};

    private final String claveMock;
    private final ObjectMapper mapper;

    public SeguridadConfig(@Value("${retail.seguridad.mock.clave}") String claveMock, ObjectMapper mapper) {
        this.claveMock = claveMock;
        this.mapper = mapper;
    }

    @Bean
    public SecurityFilterChain cadenaDeFiltros(HttpSecurity http) throws Exception {
        return http
                // La API no usa cookies de sesion, asi que CSRF no aplica: el token viaja en la cabecera.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))  // consola H2 en desarrollo
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(RUTAS_PUBLICAS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(puntoDeEntrada())
                        .accessDeniedHandler(manejadorDeAccesoDenegado()))
                .build();
    }

    /**
     * Decodificador del token.
     *
     * <p>En la Semana 8 valida la firma HMAC con la clave compartida de desarrollo. Para apuntar al
     * Seguridad real basta sustituir este bean por
     * {@code NimbusJwtDecoder.withJwkSetUri(...)} con el JWKS de G7, definido en la seccion 2.4 del
     * contrato.</p>
     */
    @Bean
    public JwtDecoder decodificadorDeToken() {
        SecretKeySpec clave = new SecretKeySpec(claveMock.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(clave).build();
    }

    /** Token ausente, vencido o con firma invalida: {@code 401} en el formato canonico. */
    @Bean
    public AuthenticationEntryPoint puntoDeEntrada() {
        return (peticion, respuesta, excepcion) -> escribirProblema(peticion, respuesta,
                HttpStatus.UNAUTHORIZED, "TOKEN_INVALIDO",
                "No autenticado", "El token esta ausente, vencido o su firma no es valida");
    }

    /** Token valido pero sin permiso suficiente: {@code 403} en el formato canonico. */
    @Bean
    public AccessDeniedHandler manejadorDeAccesoDenegado() {
        return (peticion, respuesta, excepcion) -> escribirProblema(peticion, respuesta,
                HttpStatus.FORBIDDEN, "ACCESO_DENEGADO",
                "Acceso denegado", "El token es valido pero no tiene permiso para esta operacion");
    }

    /**
     * Escribe el error con la misma estructura que usa el resto de la API.
     *
     * <p>Estos dos casos los responde el filtro de seguridad antes de llegar a un controlador, de
     * modo que no pasan por el {@code GlobalExceptionHandler}: si no se escribieran aqui, el cliente
     * recibiria un formato distinto justo en los errores mas comunes.</p>
     */
    private void escribirProblema(HttpServletRequest peticion, HttpServletResponse respuesta,
                                  HttpStatus estado, String codigo, String titulo, String detalle)
            throws IOException {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setType(URI.create("https://retail.g4/problemas/" + codigo.toLowerCase().replace('_', '-')));
        problema.setTitle(titulo);
        problema.setInstance(URI.create(peticion.getRequestURI()));
        problema.setProperty("code", codigo);
        problema.setProperty("correlationId", correlacion(peticion));
        problema.setProperty("timestamp", OffsetDateTime.now().toString());

        respuesta.setStatus(estado.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(respuesta.getOutputStream(), problema);
    }

    private String correlacion(HttpServletRequest peticion) {
        String recibido = peticion.getHeader("X-Correlation-Id");
        return (recibido == null || recibido.isBlank()) ? UUID.randomUUID().toString() : recibido;
    }
}

package pe.edu.unmsm.fisi.retail.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Traduce cualquier excepcion a la respuesta de error canonica del proyecto:
 * {@code application/problem+json} (RFC 7807), decision D-01 del contrato de API v0.4.
 *
 * <p>Los clientes deciden por {@code status} y {@code code}, nunca por el texto de {@code detail}.</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String BASE_TIPO = "https://retail.g4/problemas/";
    private static final String ENCABEZADO_CORRELACION = "X-Correlation-Id";

    /** Un campo con su motivo de rechazo, tal como viaja en {@code errores[]}. */
    public record ErrorCampo(String campo, String motivo) {
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ProblemDetail manejarNoEncontrado(RecursoNoEncontradoException ex, HttpServletRequest peticion) {
        return construir(HttpStatus.NOT_FOUND, ex.getCodigo(), "Recurso no encontrado",
                ex.getMessage(), peticion, List.of());
    }

    @ExceptionHandler(AccesoDenegadoException.class)
    public ProblemDetail manejarAccesoDenegado(AccesoDenegadoException ex, HttpServletRequest peticion) {
        return construir(HttpStatus.FORBIDDEN, ex.getCodigo(), "Acceso denegado",
                ex.getMessage(), peticion, List.of());
    }

    @ExceptionHandler(ReglaNegocioException.class)
    public ProblemDetail manejarReglaNegocio(ReglaNegocioException ex, HttpServletRequest peticion) {
        return construir(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCodigo(), "Regla de negocio no cumplida",
                ex.getMessage(), peticion, List.of());
    }

    /** Choque con el estado actual del recurso (por ejemplo, turno de caja ya abierto): {@code 409}. */
    @ExceptionHandler(ConflictoException.class)
    public ProblemDetail manejarConflicto(ConflictoException ex, HttpServletRequest peticion) {
        return construir(HttpStatus.CONFLICT, ex.getCodigo(), "Conflicto con el estado actual",
                ex.getMessage(), peticion, List.of());
    }

    /** Falla de Bean Validation sobre un cuerpo {@code @Valid}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail manejarValidacion(MethodArgumentNotValidException ex, HttpServletRequest peticion) {
        List<ErrorCampo> errores = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorCampo(error.getField(), error.getDefaultMessage()))
                .toList();
        return construir(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO", "Parametros invalidos",
                "Revise los campos indicados en errores[]", peticion, errores);
    }

    /** Parametro de query obligatorio ausente (seccion 2.6 del contrato). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail manejarParametroAusente(MissingServletRequestParameterException ex,
                                                 HttpServletRequest peticion) {
        List<ErrorCampo> errores = List.of(new ErrorCampo(ex.getParameterName(), "Es obligatorio"));
        return construir(HttpStatus.BAD_REQUEST, "PARAMETRO_OBLIGATORIO", "Falta un parametro obligatorio",
                "Falta el parametro " + ex.getParameterName(), peticion, errores);
    }

    /** Parametro con tipo o formato invalido, por ejemplo {@code size=abc} (seccion 2.6). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail manejarTipoInvalido(MethodArgumentTypeMismatchException ex,
                                             HttpServletRequest peticion) {
        List<ErrorCampo> errores = List.of(new ErrorCampo(ex.getName(), "Tipo o formato invalido"));
        return construir(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO", "Parametros invalidos",
                "El parametro " + ex.getName() + " no tiene el tipo esperado", peticion, errores);
    }

    /**
     * Ruta inexistente. Spring la senala con {@link NoResourceFoundException}; sin este manejador
     * caeria en el de {@code Exception} y responderia {@code 500}, haciendo imposible que el cliente
     * distinga "no existe" de "el servidor fallo". El contrato exige {@code 404} (seccion 2.5).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail manejarRutaInexistente(NoResourceFoundException ex, HttpServletRequest peticion) {
        return construir(HttpStatus.NOT_FOUND, "RUTA_NO_ENCONTRADA", "Recurso no encontrado",
                "La ruta solicitada no existe en este servicio", peticion, List.of());
    }

    /** Red de seguridad: nada sale del backend sin el formato canonico. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail manejarNoControlada(Exception ex, HttpServletRequest peticion) {
        log.error("Error no controlado en {} {}", peticion.getMethod(), peticion.getRequestURI(), ex);
        return construir(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "Error interno",
                "Ocurrio un error inesperado. Reintente o comuniquese con soporte", peticion, List.of());
    }

    private ProblemDetail construir(HttpStatus estado, String codigo, String titulo, String detalle,
                                    HttpServletRequest peticion, List<ErrorCampo> errores) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setType(URI.create(BASE_TIPO + codigo.toLowerCase().replace('_', '-')));
        problema.setTitle(titulo);
        problema.setInstance(URI.create(peticion.getRequestURI()));
        problema.setProperty("code", codigo);
        problema.setProperty("correlationId", correlacion(peticion));
        problema.setProperty("timestamp", OffsetDateTime.now().toString());
        if (!errores.isEmpty()) {
            problema.setProperty("errores", errores);
        }
        return problema;
    }

    /** Reutiliza el {@code X-Correlation-Id} que llega; si no llega, el backend lo genera. */
    private String correlacion(HttpServletRequest peticion) {
        String recibido = peticion.getHeader(ENCABEZADO_CORRELACION);
        return (recibido == null || recibido.isBlank()) ? UUID.randomUUID().toString() : recibido;
    }
}

package pe.edu.unmsm.fisi.retail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Plazo maximo para cambiar una prenda, contado en dias naturales desde la emision del comprobante
 * (F9, RF-23).
 *
 * <p>Los dias se cuentan con el calendario de la tienda, en hora de Peru, y no en UTC. UTC corta el dia
 * cinco horas antes: una compra a las 11:30 p. m. en Lima ya cae "al dia siguiente" en UTC, y segun la
 * hora de la consulta el cliente terminaria con un dia de plazo de mas o de menos.</p>
 *
 * <p>La duracion del plazo es configurable con {@code retail.cambios.plazo-dias}, porque los documentos
 * del proyecto no coinciden: la reparticion de la Semana 11 dice 7 dias y la spec de F9, 30.</p>
 */
@Component
public class PoliticaPlazoCambio {

    static final ZoneId ZONA_TIENDA = ZoneId.of("America/Lima");

    private final int plazoDias;
    private final Clock reloj;

    @Autowired
    public PoliticaPlazoCambio(@Value("${retail.cambios.plazo-dias:7}") int plazoDias) {
        this(plazoDias, Clock.systemUTC());
    }

    /** Para pruebas: permite fijar el "ahora". */
    PoliticaPlazoCambio(int plazoDias, Clock reloj) {
        if (plazoDias < 1) {
            throw new IllegalArgumentException("El plazo de cambio debe ser de al menos 1 dia: " + plazoDias);
        }
        this.plazoDias = plazoDias;
        this.reloj = Objects.requireNonNull(reloj, "reloj");
    }

    /**
     * Evalua si un comprobante emitido en {@code fechaEmision} sigue dentro del plazo de cambio.
     *
     * <p>El dia de la compra es el dia 0, y el ultimo dia del plazo todavia es valido: con 7 dias de
     * plazo, una compra del lunes se puede cambiar hasta el lunes siguiente inclusive.</p>
     */
    public Evaluacion evaluar(Instant fechaEmision) {
        Objects.requireNonNull(fechaEmision, "fechaEmision");

        LocalDate diaCompra = fechaEmision.atZone(ZONA_TIENDA).toLocalDate();
        LocalDate hoy = LocalDate.now(reloj.withZone(ZONA_TIENDA));
        // Una fecha futura no deberia existir, pero si llega por un reloj desfasado no se cuenta en negativo.
        long transcurridos = Math.max(0, ChronoUnit.DAYS.between(diaCompra, hoy));

        return new Evaluacion(transcurridos, plazoDias, transcurridos <= plazoDias);
    }

    /**
     * @param diasTranscurridos dias naturales desde la compra, contando la compra como el dia 0
     * @param plazoDias         plazo vigente
     * @param plazoValido       si todavia se puede cambiar
     */
    public record Evaluacion(long diasTranscurridos, int plazoDias, boolean plazoValido) {
    }
}

package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas del plazo de cambio (F9, RF-23).
 *
 * <p>Criterios de aceptacion verificados: un comprobante dentro del plazo permite el cambio y uno que
 * lo excede lo bloquea. Se prueba ademas el borde del ultimo dia y que los dias se cuenten en hora de
 * Peru, no en UTC.</p>
 */
class PoliticaPlazoCambioTest {

    /** "Ahora": sabado 7 de noviembre de 2026, 10:00 en Lima (15:00 UTC). */
    private static final Clock AHORA = Clock.fixed(Instant.parse("2026-11-07T15:00:00Z"), ZoneOffset.UTC);

    private final PoliticaPlazoCambio politica = new PoliticaPlazoCambio(7, AHORA);

    @Test
    @DisplayName("Una compra de hoy es el dia 0 y esta en plazo")
    void compraDeHoyEstaEnPlazo() {
        var evaluacion = politica.evaluar(Instant.parse("2026-11-07T14:00:00Z"));

        assertThat(evaluacion.diasTranscurridos()).isZero();
        assertThat(evaluacion.plazoValido()).isTrue();
        assertThat(evaluacion.plazoDias()).isEqualTo(7);
    }

    @Test
    @DisplayName("El ultimo dia del plazo todavia permite el cambio")
    void elUltimoDiaEsValido() {
        var evaluacion = politica.evaluar(Instant.parse("2026-10-31T15:00:00Z"));

        assertThat(evaluacion.diasTranscurridos()).isEqualTo(7);
        assertThat(evaluacion.plazoValido()).isTrue();
    }

    @Test
    @DisplayName("Un dia despues del plazo, el cambio se bloquea")
    void unDiaDespuesSeBloquea() {
        var evaluacion = politica.evaluar(Instant.parse("2026-10-30T15:00:00Z"));

        assertThat(evaluacion.diasTranscurridos()).isEqualTo(8);
        assertThat(evaluacion.plazoValido()).isFalse();
    }

    @Test
    @DisplayName("Los dias se cuentan con el calendario de Lima, no con el de UTC")
    void cuentaConElCalendarioDeLaTienda() {
        // 31/10 a las 23:30 en Lima es 1/11 a las 04:30 en UTC. Para la tienda la compra fue el 31/10,
        // asi que hoy es el dia 7. Contando en UTC saldria 6 y el cliente tendria un dia de mas.
        var evaluacion = politica.evaluar(Instant.parse("2026-11-01T04:30:00Z"));

        assertThat(evaluacion.diasTranscurridos()).isEqualTo(7);
        assertThat(evaluacion.plazoValido()).isTrue();
    }

    @Test
    @DisplayName("Una fecha de emision futura no cuenta dias negativos")
    void fechaFuturaNoCuentaNegativo() {
        var evaluacion = politica.evaluar(Instant.parse("2026-11-09T15:00:00Z"));

        assertThat(evaluacion.diasTranscurridos()).isZero();
        assertThat(evaluacion.plazoValido()).isTrue();
    }

    @Test
    @DisplayName("El plazo es configurable: con 30 dias, una compra de hace 20 sigue en plazo")
    void elPlazoEsConfigurable() {
        var politica30 = new PoliticaPlazoCambio(30, AHORA);

        var evaluacion = politica30.evaluar(Instant.parse("2026-10-18T15:00:00Z"));

        assertThat(evaluacion.diasTranscurridos()).isEqualTo(20);
        assertThat(evaluacion.plazoValido()).isTrue();
    }

    @Test
    @DisplayName("Un plazo de cero dias o negativo es un error de configuracion")
    void rechazaPlazosInvalidos() {
        assertThatThrownBy(() -> new PoliticaPlazoCambio(0, AHORA)).isInstanceOf(IllegalArgumentException.class);
    }
}

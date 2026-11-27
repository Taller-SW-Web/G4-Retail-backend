package pe.edu.unmsm.fisi.retail.config.gestionapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Ventanas de un minuto del límite de peticiones (contrato v0.5, §2.8). */
@DisplayName("LimitadorPeticiones — ventana fija por minuto")
class LimitadorPeticionesTest {

    @Test
    @DisplayName("Permite hasta el límite, rechaza la siguiente e informa cuántas quedan")
    void permiteHastaElLimite() {
        RelojMovil reloj = new RelojMovil(Instant.parse("2026-11-26T15:00:10Z"));
        LimitadorPeticiones limitador = new LimitadorPeticiones(2, reloj);

        assertThat(limitador.consumir("usuario:a").restantes()).isEqualTo(1);
        assertThat(limitador.consumir("usuario:a").permitido()).isTrue();
        LimitadorPeticiones.Resultado tercera = limitador.consumir("usuario:a");

        assertThat(tercera.permitido()).isFalse();
        assertThat(tercera.restantes()).isZero();
        assertThat(tercera.reintentarEnSegundos()).isEqualTo(50);
    }

    @Test
    @DisplayName("Cada vendedor tiene su propio contador")
    void contadorPorCliente() {
        LimitadorPeticiones limitador = new LimitadorPeticiones(1, new RelojMovil(Instant.parse("2026-11-26T15:00:00Z")));

        assertThat(limitador.consumir("usuario:a").permitido()).isTrue();
        assertThat(limitador.consumir("usuario:b").permitido()).isTrue();
        assertThat(limitador.consumir("usuario:a").permitido()).isFalse();
    }

    @Test
    @DisplayName("Al empezar el minuto siguiente el contador vuelve a cero y la limpieza borra la ventana vieja")
    void nuevaVentana() {
        RelojMovil reloj = new RelojMovil(Instant.parse("2026-11-26T15:00:59Z"));
        LimitadorPeticiones limitador = new LimitadorPeticiones(1, reloj);
        limitador.consumir("usuario:a");
        assertThat(limitador.consumir("usuario:a").permitido()).isFalse();

        reloj.avanzar(Duration.ofSeconds(2));
        assertThat(limitador.consumir("usuario:a").permitido()).isTrue();

        limitador.consumir("usuario:b");
        reloj.avanzar(Duration.ofMinutes(1));
        limitador.limpiarVencidas();
        assertThat(limitador.clientesActivos()).isZero();
    }

    /** Reloj que la prueba hace avanzar. */
    static final class RelojMovil extends Clock {
        private Instant ahora;

        RelojMovil(Instant inicio) {
            this.ahora = inicio;
        }

        void avanzar(Duration d) {
            ahora = ahora.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora; }
    }
}

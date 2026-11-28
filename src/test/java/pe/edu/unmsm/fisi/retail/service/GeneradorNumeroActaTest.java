package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Numero del acta de merma (F12, RF-34, CA-RF34-03: correlativo inmutable).
 */
class GeneradorNumeroActaTest {

    private static Clock relojEn(String instante) {
        return Clock.fixed(Instant.parse(instante), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("Arma el numero con el año y cuatro digitos, como en el contrato")
    void formatoDelContrato() {
        var generador = new GeneradorNumeroActa(relojEn("2026-11-25T15:00:00Z"), () -> 12);

        assertThat(generador.siguiente()).isEqualTo("ACTA-MERMA-2026-0012");
    }

    @Test
    @DisplayName("Cada acta toma un correlativo nuevo de la secuencia")
    void correlativosConsecutivos() {
        var secuencia = new AtomicLong();
        var generador = new GeneradorNumeroActa(relojEn("2026-11-25T15:00:00Z"), secuencia::incrementAndGet);

        assertThat(generador.siguiente()).isEqualTo("ACTA-MERMA-2026-0001");
        assertThat(generador.siguiente()).isEqualTo("ACTA-MERMA-2026-0002");
    }

    @Test
    @DisplayName("El año es el de la tienda en Lima, no el de UTC")
    void anioEnLaZonaDeLaTienda() {
        // 1 de enero de 2027 a las 03:00 UTC sigue siendo 31 de diciembre de 2026 en Lima (UTC-5).
        var generador = new GeneradorNumeroActa(relojEn("2027-01-01T03:00:00Z"), () -> 7);

        assertThat(generador.siguiente()).isEqualTo("ACTA-MERMA-2026-0007");
    }

    @Test
    @DisplayName("Pasado el 9999 sigue con cinco digitos en lugar de cortar el numero")
    void noTruncaElCorrelativo() {
        var generador = new GeneradorNumeroActa(relojEn("2026-11-25T15:00:00Z"), () -> 10_000);

        assertThat(generador.siguiente()).isEqualTo("ACTA-MERMA-2026-10000");
    }

    @Test
    @DisplayName("Rechaza un correlativo no valido de la secuencia")
    void rechazaCorrelativoInvalido() {
        var generador = new GeneradorNumeroActa(relojEn("2026-11-25T15:00:00Z"), () -> 0);

        assertThatThrownBy(generador::siguiente).isInstanceOf(IllegalStateException.class);
    }
}

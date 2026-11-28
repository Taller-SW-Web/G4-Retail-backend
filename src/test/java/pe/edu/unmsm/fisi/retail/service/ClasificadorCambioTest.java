package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.model.TipoCambio;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tipo de un cambio directo segun el valor (F9, contrato 5.9). */
class ClasificadorCambioTest {

    private static final BigDecimal CREDITO = new BigDecimal("59.90");

    @Test
    @DisplayName("Mismo modelo es siempre mismo valor, aunque el precio vigente haya subido")
    void mismoModeloIgnoraElPrecio() {
        assertThat(ClasificadorCambio.clasificar(CREDITO, new BigDecimal("69.90"), true)).isEqualTo(TipoCambio.MISMO_VALOR);
    }

    @Test
    @DisplayName("Mismo modelo es mismo valor aunque el precio vigente haya bajado")
    void mismoModeloConPrecioMenor() {
        assertThat(ClasificadorCambio.clasificar(CREDITO, new BigDecimal("49.90"), true)).isEqualTo(TipoCambio.MISMO_VALOR);
    }

    @Test
    @DisplayName("Otro modelo con el mismo total es mismo valor")
    void otroModeloMismoTotal() {
        assertThat(ClasificadorCambio.clasificar(CREDITO, new BigDecimal("59.90"), false)).isEqualTo(TipoCambio.MISMO_VALOR);
    }

    @Test
    @DisplayName("Otro modelo mas caro es mayor valor")
    void otroModeloMasCaro() {
        assertThat(ClasificadorCambio.clasificar(CREDITO, new BigDecimal("89.00"), false)).isEqualTo(TipoCambio.MAYOR_VALOR);
    }

    @Test
    @DisplayName("Otro modelo mas barato se rechaza con CAMBIO_MENOR_VALOR")
    void otroModeloMasBarato() {
        assertThatThrownBy(() -> ClasificadorCambio.clasificar(CREDITO, new BigDecimal("39.90"), false))
                .isInstanceOf(ReglaNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMBIO_MENOR_VALOR");
    }
}

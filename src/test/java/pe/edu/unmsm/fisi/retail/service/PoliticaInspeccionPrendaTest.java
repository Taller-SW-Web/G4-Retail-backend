package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pe.edu.unmsm.fisi.retail.dto.InspeccionPrenda;
import pe.edu.unmsm.fisi.retail.model.DestinoPrenda;
import pe.edu.unmsm.fisi.retail.model.EstadoAprobacionCambio;
import pe.edu.unmsm.fisi.retail.model.MotivoCambio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de la inspeccion fisica en mostrador (F9, RF-24).
 *
 * <p>Criterios de aceptacion verificados: un cambio de talla sin etiquetas intactas se rechaza; con el
 * checklist completo se aprueba y sugiere reingreso al inventario; y una falla de fabrica se aprueba
 * por garantia aunque falten las etiquetas.</p>
 */
class PoliticaInspeccionPrendaTest {

    private final PoliticaInspeccionPrenda politica = new PoliticaInspeccionPrenda();

    private static final InspeccionPrenda IMPECABLE = new InspeccionPrenda(true, true, true);

    @Test
    @DisplayName("Cambio de talla con checklist completo: se aprueba y sugiere reingreso")
    void cambioDeTallaCompletoSeAprueba() {
        var resultado = politica.evaluar(MotivoCambio.CAMBIO_TALLA, IMPECABLE);

        assertThat(resultado.aprobado()).isTrue();
        assertThat(resultado.estado()).isEqualTo(EstadoAprobacionCambio.APROBADO_EN_TIENDA);
        assertThat(resultado.destino()).isEqualTo(DestinoPrenda.REINGRESO_INVENTARIO);
        assertThat(resultado.observaciones()).isEmpty();
    }

    @Test
    @DisplayName("Cambio de talla sin etiquetas intactas: se rechaza y dice por que")
    void cambioDeTallaSinEtiquetasSeRechaza() {
        var resultado = politica.evaluar(MotivoCambio.CAMBIO_TALLA, new InspeccionPrenda(false, true, true));

        assertThat(resultado.aprobado()).isFalse();
        assertThat(resultado.estado()).isEqualTo(EstadoAprobacionCambio.RECHAZADO);
        assertThat(resultado.destino()).isNull();
        assertThat(resultado.observaciones()).containsExactly(PoliticaInspeccionPrenda.SIN_ETIQUETAS);
    }

    @Test
    @DisplayName("Cambio de talla de una prenda usada: se rechaza")
    void cambioDeTallaConUsoSeRechaza() {
        var resultado = politica.evaluar(MotivoCambio.CAMBIO_TALLA, new InspeccionPrenda(true, false, true));

        assertThat(resultado.estado()).isEqualTo(EstadoAprobacionCambio.RECHAZADO);
        assertThat(resultado.observaciones()).containsExactly(PoliticaInspeccionPrenda.CON_USO);
    }

    @Test
    @DisplayName("Si fallan varias condiciones, el rechazo las lista todas")
    void listaTodasLasCondicionesIncumplidas() {
        var resultado = politica.evaluar(MotivoCambio.CAMBIO_TALLA, new InspeccionPrenda(false, false, false));

        assertThat(resultado.observaciones()).containsExactly(
                PoliticaInspeccionPrenda.SIN_ETIQUETAS,
                PoliticaInspeccionPrenda.CON_USO,
                PoliticaInspeccionPrenda.SIN_EMPAQUE);
    }

    @Test
    @DisplayName("Un cambio de modelo tiene las mismas exigencias que uno de talla")
    void cambioDeModeloEsOrdinario() {
        var resultado = politica.evaluar(MotivoCambio.CAMBIO_MODELO, new InspeccionPrenda(false, true, true));

        assertThat(resultado.estado()).isEqualTo(EstadoAprobacionCambio.RECHAZADO);
    }

    @Test
    @DisplayName("Falla de fabrica sin etiquetas: se aprueba por garantia y va a merma")
    void fallaDeFabricaSeApruebaPorGarantia() {
        var resultado = politica.evaluar(MotivoCambio.FALLA_FABRICA, new InspeccionPrenda(false, false, true));

        assertThat(resultado.aprobado()).isTrue();
        assertThat(resultado.destino()).isEqualTo(DestinoPrenda.MERMA_GARANTIA);
        assertThat(resultado.observaciones()).containsExactly(PoliticaInspeccionPrenda.POR_GARANTIA);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MotivoCambio.class)
    @DisplayName("El empaque original no bloquea el cambio, pero queda anotado")
    void elEmpaqueNoBloquea(MotivoCambio motivo) {
        var resultado = politica.evaluar(motivo, new InspeccionPrenda(true, true, false));

        assertThat(resultado.aprobado()).isTrue();
        assertThat(resultado.observaciones()).contains(PoliticaInspeccionPrenda.SIN_EMPAQUE);
    }

    @Test
    @DisplayName("Sin motivo o sin inspeccion no se decide nada")
    void exigeMotivoEInspeccion() {
        assertThatThrownBy(() -> politica.evaluar(null, IMPECABLE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> politica.evaluar(MotivoCambio.CAMBIO_TALLA, null))
                .isInstanceOf(NullPointerException.class);
    }
}

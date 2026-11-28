package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.model.EstadoIncidencia;
import pe.edu.unmsm.fisi.retail.repository.IncidenciaInventarioRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contrato 5.9 y 5.12: las prendas nuevas de un cambio directo (F9) no pueden estar en cuarentena
 * activa de F12 en la tienda del perfil ({@code 422 SKU_EN_CUARENTENA}).
 */
@DisplayName("SkusEnCuarentenaF12 — el cambio directo respeta la cuarentena de F12")
class SkusEnCuarentenaF12Test {

    private static final String UUID_TIENDA = "11111111-1111-1111-1111-111111111111";
    private static final String TIENDA = "TIENDA-MIRAFLORES";

    private IncidenciaInventarioRepository incidencias;
    private SkusEnCuarentenaF12 cuarentena;

    @BeforeEach
    void preparar() {
        incidencias = mock(IncidenciaInventarioRepository.class);
        cuarentena = new SkusEnCuarentenaF12(incidencias, new CodigosSucursal(UUID_TIENDA + "=" + TIENDA));
        when(incidencias.skusConEstado(anyString(), anyCollection(), eq(EstadoIncidencia.CUARENTENA_ACTIVA)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("Una prenda con incidencia activa en la tienda queda bloqueada para el cambio")
    void prendaEnCuarentenaBloqueada() {
        when(incidencias.skusConEstado(TIENDA, List.of("CAM-PERU-M-BLA"), EstadoIncidencia.CUARENTENA_ACTIVA))
                .thenReturn(List.of("CAM-PERU-M-BLA"));

        assertThat(cuarentena.enCuarentena(UUID_TIENDA, "CAM-PERU-M-BLA")).isTrue();
    }

    @Test
    @DisplayName("Una prenda sin incidencia activa sí puede entregarse")
    void prendaLibre() {
        assertThat(cuarentena.enCuarentena(UUID_TIENDA, "ZAP-42")).isFalse();
    }

    @Test
    @DisplayName("La tienda llega como código de sucursal y se consulta igual")
    void tiendaComoCodigo() {
        when(incidencias.skusConEstado(TIENDA, List.of("ZAP-42"), EstadoIncidencia.CUARENTENA_ACTIVA))
                .thenReturn(List.of("ZAP-42"));

        assertThat(cuarentena.enCuarentena(TIENDA, "ZAP-42")).isTrue();
    }

    @Test
    @DisplayName("Sin tienda o sin SKU no consulta la cuarentena")
    void sinDatos() {
        assertThat(cuarentena.enCuarentena(null, "A")).isFalse();
        assertThat(cuarentena.enCuarentena(UUID_TIENDA, null)).isFalse();
        verifyNoInteractions(incidencias);
    }
}

package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoResponse;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.integracion.productos.mock.PromocionesEnMemoria;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tarea B4 (RF-33): el carrito rechaza las prendas en cuarentena activa de la tienda del vendedor.
 */
@DisplayName("CarritoService — bloqueo de SKU en cuarentena (RF-33)")
class CartServiceCuarentenaTest {

    private static final UUID VENDEDOR = UUID.randomUUID();

    private CuarentenaService cuarentena;
    private CarritoService servicio;

    @BeforeEach
    void preparar() {
        cuarentena = mock(CuarentenaService.class);
        servicio = new CarritoService(new PromocionesEnMemoria());
        servicio.setCuarentena(cuarentena);
    }

    @Test
    @DisplayName("CA-RF33-02: agregar una prenda en cuarentena responde 422 SKU_EN_CUARENTENA con alerta explícita")
    void rechazaSkuEnCuarentena() {
        when(cuarentena.skusBloqueados(eq(VENDEDOR), any())).thenReturn(Set.of("CAM-PERU-M-BLA"));

        assertThatThrownBy(() -> servicio.calcularTotales(carrito("CAM-PERU-M-BLA", "ZAP-42"), VENDEDOR))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessageContaining("PRODUCTO BLOQUEADO POR CUARENTENA")
                .hasMessageContaining("CAM-PERU-M-BLA")
                .extracting("codigo").isEqualTo("SKU_EN_CUARENTENA");
    }

    @Test
    @DisplayName("Sin prendas en cuarentena calcula los totales como siempre")
    void sinCuarentenaCalcula() {
        when(cuarentena.skusBloqueados(eq(VENDEDOR), any())).thenReturn(Set.of());

        CalculoCarritoResponse respuesta = servicio.calcularTotales(carrito("ZAP-42"), VENDEDOR);

        assertThat(respuesta.totalPagar()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Un carrito vacío sigue respondiendo CARRITO_VACIO, sin consultar la cuarentena")
    void carritoVacioNoConsultaCuarentena() {
        assertThatThrownBy(() -> servicio.calcularTotales(new CalculoCarritoRequest(List.of(), null), VENDEDOR))
                .isInstanceOf(ReglaNegocioException.class)
                .extracting("codigo").isEqualTo("CARRITO_VACIO");
    }

    private static CalculoCarritoRequest carrito(String... skus) {
        return new CalculoCarritoRequest(List.of(skus).stream()
                .map(sku -> new CalculoCarritoRequest.ItemCarritoCalculoDto(sku, 1, new BigDecimal("100.00")))
                .toList(), null);
    }
}

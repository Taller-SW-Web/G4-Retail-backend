package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoResponse;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.Precio;
import pe.edu.unmsm.fisi.retail.integracion.productos.mock.PromocionesEnMemoria;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contrato §5.4: en {@code POST /carritos/calcular} el precio lo obtiene el backend de Productos (G6),
 * nunca se confía en el precio enviado por el cliente, y un SKU sin precio responde {@code 404 SKU_NO_ENCONTRADO}.
 */
@DisplayName("CarritoService — precio vigente de Productos (G6)")
class CarritoPrecioProductosTest {

    private final ProductosPort productos = mock(ProductosPort.class);
    private final CarritoService servicio = new CarritoService(new PromocionesEnMemoria(), productos);

    private static CalculoCarritoRequest carrito(BigDecimal precioCliente) {
        return new CalculoCarritoRequest(List.of(
                new CalculoCarritoRequest.ItemCarritoCalculoDto("ADI-HT5027-NEG-S", 2, precioCliente)), null);
    }

    @Test
    @DisplayName("Usa el precio de G6 (con la oferta si la hay) e ignora el que manda el cliente")
    void usaPrecioDeProductos() {
        when(productos.consultarPrecio("ADI-HT5027-NEG-S")).thenReturn(Optional.of(
                new Precio("ADI-HT5027-NEG-S", new BigDecimal("69.00"), new BigDecimal("59.00"), "PEN")));

        CalculoCarritoResponse r = servicio.calcularTotales(carrito(new BigDecimal("1.00")));

        assertThat(r.lineas().get(0).precioUnitario()).isEqualByComparingTo("59.00");
        assertThat(r.subtotalBruto()).isEqualByComparingTo("118.00");
        assertThat(r.totalPagar()).isEqualByComparingTo("118.00");
    }

    @Test
    @DisplayName("Un SKU sin precio vigente responde SKU_NO_ENCONTRADO")
    void skuSinPrecio() {
        when(productos.consultarPrecio("ADI-HT5027-NEG-S")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.calcularTotales(carrito(null)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting("codigo").isEqualTo("SKU_NO_ENCONTRADO");
    }

    @Test
    @DisplayName("Si Productos no responde, el cálculo no inventa un precio: responde 503")
    void productosCaido() {
        when(productos.consultarPrecio("ADI-HT5027-NEG-S"))
                .thenThrow(new ServicioExternoNoDisponibleException("PRODUCTOS_NO_DISPONIBLE", "caido"));

        assertThatThrownBy(() -> servicio.calcularTotales(carrito(new BigDecimal("59.00"))))
                .isInstanceOf(ServicioExternoNoDisponibleException.class);
    }
}

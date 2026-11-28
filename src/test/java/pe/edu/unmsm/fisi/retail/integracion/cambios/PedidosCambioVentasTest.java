package pe.edu.unmsm.fisi.retail.integracion.cambios;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.client.VentasClient;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PedidosCambioVentas — adaptador real del pedido del cambio (F9)")
class PedidosCambioVentasTest {

    @Test
    @DisplayName("Envia el pedido a Ventas con la misma clave y devuelve su resultado")
    void delegaEnVentas() {
        VentasClient ventas = mock(VentasClient.class);
        VentasCrearPedidoRequest pedido = new VentasCrearPedidoRequest("RETAIL", null, null, null, null, null);
        ResultadoPedidoCambio esperado = new ResultadoPedidoCambio.Confirmado("PED-1", "PAGADO");
        when(ventas.crearPedidoCambio(pedido, "clave-1")).thenReturn(esperado);

        assertThat(new PedidosCambioVentas(ventas).enviar(pedido, "clave-1")).isEqualTo(esperado);
    }
}

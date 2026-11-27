package pe.edu.unmsm.fisi.retail.integracion.cambios;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import pe.edu.unmsm.fisi.retail.client.VentasClient;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;

/** Pedido del cambio de prenda contra Ventas (G5) real, por su API Gateway. */
@Component
@Profile("!mock")
@RequiredArgsConstructor
public class PedidosCambioVentas implements PedidosCambioPort {

    private final VentasClient ventas;

    @Override
    public ResultadoPedidoCambio enviar(VentasCrearPedidoRequest pedido, String clave) {
        return ventas.crearPedidoCambio(pedido, clave);
    }
}

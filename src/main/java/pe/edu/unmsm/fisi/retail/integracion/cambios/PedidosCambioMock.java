package pe.edu.unmsm.fisi.retail.integracion.cambios;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Doble de Ventas para el pedido del cambio de prenda (perfil {@code mock}).
 *
 * <p>G5 aun no publica {@code CREDITO_CAMBIO}; mientras tanto el cambio se prueba contra este doble,
 * como indica el contrato. Confirma siempre y, con la misma clave, devuelve el mismo pedido.</p>
 */
@Component
@Profile("mock")
public class PedidosCambioMock implements PedidosCambioPort {

    private final AtomicInteger secuencia = new AtomicInteger(900);
    private final Map<String, String> pedidosPorClave = new ConcurrentHashMap<>();

    @Override
    public ResultadoPedidoCambio enviar(VentasCrearPedidoRequest pedido, String clave) {
        String pedidoId = pedidosPorClave.computeIfAbsent(clave,
                c -> String.format("PED-2026-%05d", secuencia.incrementAndGet()));
        return new ResultadoPedidoCambio.Confirmado(pedidoId, "PAGADO");
    }
}

package pe.edu.unmsm.fisi.retail.integracion.ventas;

import pe.edu.unmsm.fisi.retail.dto.EntregaPickupRequest;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse.Articulo;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pedidos pickup de ejemplo, en memoria, con el mismo comportamiento que Ventas (G5).
 *
 * <p>Se usa en las pruebas y cuando el backend corre sin credenciales de servicio de G7. Ignora la
 * tienda: siempre devuelve los mismos pedidos. Las entregas se pierden al reiniciar.</p>
 */
public class PickupVentasEnMemoria implements PickupVentasPort {

    static final String LISTO_PARA_RECOJO = "LISTO_PARA_RECOJO";

    private final Map<String, PedidoPickupResponse> pedidos = new ConcurrentHashMap<>();
    private final Set<String> entregados = ConcurrentHashMap.newKeySet();

    public PickupVentasEnMemoria() {
        List.of(
                pedido("PED-2026-08910", "María González", "45678912", "Anaquel B-04", 3, LISTO_PARA_RECOJO,
                        new Articulo("ZAP-RUN-41-BLANC", "Zapatilla Running Pegasus 40", "41", "Blanco", 1)),
                pedido("PED-2026-08914", "Juan Pérez Torres", "72345678", "Estante A - Casillero 14", 5, LISTO_PARA_RECOJO,
                        new Articulo("CAM-PERU-M-BLANC", "Camiseta Selección 2026", "M", "Blanco", 2),
                        new Articulo("BAL-FUT-5-BLANC", "Balón de Fútbol Match N.° 5", "5", "Blanco", 1)),
                pedido("PED-2026-08897", "Rosa Quispe Huamán", "40123987", "Anaquel C-11", 27, LISTO_PARA_RECOJO,
                        new Articulo("LEG-TRA-S-NEGRO", "Leggings Training Compresión", "S", "Negro", 1)),
                pedido("PED-2026-08871", "Diego Flores Ccori", "70881234", "Estante A - Casillero 02", 50, LISTO_PARA_RECOJO,
                        new Articulo("CAS-OUT-L-VERDE", "Casaca Cortaviento Trail", "L", "Verde", 1)),
                pedido("PED-2026-08932", "Lucero Vargas Paz", "48765432", "", 0, "EN_CAMINO_A_TIENDA",
                        new Articulo("MOC-OUT-30L-GRIS", "Mochila Trekking 30 L", "30L", "Gris", 1))
        ).forEach(p -> pedidos.put(p.pedidoId(), p));
    }

    @Override
    public List<PedidoPickupResponse> listarPendientes(String tiendaId) {
        return pedidos.values().stream()
                .filter(p -> !entregados.contains(p.pedidoId()))
                .sorted((a, b) -> b.fechaArriboTienda().compareTo(a.fechaArriboTienda()))
                .toList();
    }

    @Override
    public EntregaPickupResponse confirmarEntrega(String pedidoId, EntregaPickupRequest entrega) {
        PedidoPickupResponse pedido = pedidos.get(pedidoId);
        if (pedido == null) {
            throw new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO", "No existe el pedido " + pedidoId);
        }
        if (!LISTO_PARA_RECOJO.equals(pedido.estado()) || !entregados.add(pedidoId)) {
            throw new ConflictoException("PEDIDO_NO_LISTO_PARA_RECOJO",
                    "El paquete no figura en estado listo para recojo");
        }
        return new EntregaPickupResponse("CONST-" + pedidoId, Instant.now().toString(), "ENTREGADO");
    }

    @Override
    public String origen() {
        return "EN_MEMORIA";
    }

    private static PedidoPickupResponse pedido(String id, String cliente, String dni, String anaquel,
                                               int haceHoras, String estado, Articulo... articulos) {
        String arribo = Instant.now().minus(haceHoras, ChronoUnit.HOURS).toString();
        return new PedidoPickupResponse(id, cliente, dni, anaquel, arribo, estado, List.of(articulos));
    }
}

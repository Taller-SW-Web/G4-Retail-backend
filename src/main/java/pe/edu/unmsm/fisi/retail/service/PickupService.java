package pe.edu.unmsm.fisi.retail.service;

import org.springframework.stereotype.Service;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bandeja y entrega de pedidos pickup en tienda (F7): RF-18 y RF-19.
 *
 * <p>El dueño del pedido es Ventas (G5) y la decisión de quién publica el estado
 * {@code LISTO_PARA_RECOJO} sigue pendiente (decisión #9 del contrato). Mientras tanto, para la
 * Semana 8, el servicio trabaja con pedidos de ejemplo en memoria. Cuando exista el endpoint de
 * Ventas, solo cambia de dónde salen los pedidos; las reglas de este servicio se mantienen.</p>
 *
 * <p>Límite estricto: solo entrega paquetes ya pagados. No crea pedidos ni cobra dinero.</p>
 */
@Service
public class PickupService {

    static final String LISTO_PARA_RECOJO = "LISTO_PARA_RECOJO";

    private final Map<String, PedidoPickupResponse> pedidos = new ConcurrentHashMap<>();
    private final Set<String> entregados = ConcurrentHashMap.newKeySet();

    public PickupService() {
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

    /** RF-18: pedidos pickup de la tienda que todavía no se entregan, del más reciente al más antiguo. */
    public List<PedidoPickupResponse> pendientes() {
        return pedidos.values().stream()
                .filter(p -> !entregados.contains(p.pedidoId()))
                .sorted((a, b) -> b.fechaArriboTienda().compareTo(a.fechaArriboTienda()))
                .toList();
    }

    /**
     * RF-19: registra la entrega física y emite la constancia.
     *
     * <p>{@code 404} si el pedido no existe; {@code 409} si todavía no está listo para recojo o ya se
     * entregó.</p>
     *
     * @param encargadoId vendedor que entrega, tomado del token
     */
    public EntregaPickupResponse entregar(String pedidoId, UUID encargadoId, EntregaPickupRequest peticion) {
        PedidoPickupResponse pedido = pedidos.get(pedidoId);
        if (pedido == null) {
            throw new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                    "No existe el pedido " + pedidoId);
        }
        if (!LISTO_PARA_RECOJO.equals(pedido.estado()) || !entregados.add(pedidoId)) {
            throw new ConflictoException("PEDIDO_NO_LISTO_PARA_RECOJO",
                    "El paquete no figura en estado listo para recojo");
        }
        return new EntregaPickupResponse("CONST-" + pedidoId, Instant.now().toString(), "ENTREGADO");
    }

    private static PedidoPickupResponse pedido(String id, String cliente, String dni, String anaquel,
                                               int haceHoras, String estado, Articulo... articulos) {
        String arribo = Instant.now().minus(haceHoras, ChronoUnit.HOURS).toString();
        return new PedidoPickupResponse(id, cliente, dni, anaquel, arribo, estado, List.of(articulos));
    }
}

package pe.edu.unmsm.fisi.retail.integracion.ventas;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Índice local de los pedidos emitidos en este mostrador, por documento del comprador.
 *
 * <p>Existe porque G5 no deja listar pedidos con el token de servicio de Retail: el listado
 * ({@code GET /api/v1/pedidos}) es solo para el rol {@code ADMIN_VENTAS}, y el scope
 * {@code ventas:pedidos:consultar} solo sirve para leer un pedido por su id. Por eso la búsqueda por
 * DNI/RUC sale de aquí y el estado actual se pide a G5 pedido por pedido.</p>
 *
 * <p>Vive en memoria: solo cubre las ventas de la ejecución actual y se pierde al reiniciar. No es el
 * histórico del cliente; ese es RF-16-DOC (spec F6 §3.1), pendiente del acuerdo de acceso con G5.</p>
 */
@Component
public class RegistroPedidosMostrador {

    private final Map<String, Set<String>> pedidosPorDocumento = new ConcurrentHashMap<>();

    /** Asocia el pedido al documento del comprador. Ignora documentos o pedidos vacíos. */
    public void registrar(String documento, String pedidoId) {
        if (documento == null || documento.isBlank() || pedidoId == null || pedidoId.isBlank()) {
            return;
        }
        Set<String> pedidos = pedidosPorDocumento.computeIfAbsent(documento.trim(), clave -> new LinkedHashSet<>());
        synchronized (pedidos) {
            pedidos.add(pedidoId.trim());
        }
    }

    /** Pedidos emitidos para ese documento, en el orden en que se registraron; vacía si no hay ninguno. */
    public List<String> pedidosDe(String documento) {
        if (documento == null) {
            return List.of();
        }
        Set<String> pedidos = pedidosPorDocumento.get(documento.trim());
        if (pedidos == null) {
            return List.of();
        }
        synchronized (pedidos) {
            return List.copyOf(pedidos);
        }
    }
}

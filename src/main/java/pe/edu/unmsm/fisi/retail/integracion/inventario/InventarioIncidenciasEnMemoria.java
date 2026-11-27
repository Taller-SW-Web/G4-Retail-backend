package pe.edu.unmsm.fisi.retail.integracion.inventario;

import lombok.extern.slf4j.Slf4j;
import pe.edu.unmsm.fisi.retail.model.OutboxEvento;
import pe.edu.unmsm.fisi.retail.service.outbox.DestinoOutbox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Doble de Inventory (G6) para incidencias, mientras G6 confirma su ruta (contrato v0.5, §6).
 *
 * <p>Acepta cada incidencia y devuelve un {@code incidenciaInventoryId} {@code INC-INV-NNNNN}. Respeta
 * la {@code Idempotency-Key}: la misma incidencia enviada dos veces devuelve el mismo id.</p>
 */
@Slf4j
public class InventarioIncidenciasEnMemoria implements DestinoOutbox {

    private final Map<String, String> recibidas = new ConcurrentHashMap<>();
    private final AtomicInteger correlativo = new AtomicInteger();

    @Override
    public String nombre() {
        return PRODUCTOS_G6;
    }

    @Override
    public String enviar(OutboxEvento evento) {
        String id = recibidas.computeIfAbsent(evento.getAgregadoId(),
                clave -> "INC-INV-%05d".formatted(correlativo.incrementAndGet()));
        log.info("Inventory simulado aceptó la incidencia {} como {}", evento.getAgregadoId(), id);
        return id;
    }

    @Override
    public String origen() {
        return "EN_MEMORIA";
    }

    /** Cuántas incidencias distintas recibió; sirve a las pruebas. */
    public int totalRecibidas() {
        return recibidas.size();
    }
}

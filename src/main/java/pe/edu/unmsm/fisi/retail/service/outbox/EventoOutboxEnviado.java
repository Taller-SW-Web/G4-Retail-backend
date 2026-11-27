package pe.edu.unmsm.fisi.retail.service.outbox;

/**
 * Se publica dentro de {@code ms-retail} cuando el destino acepta un evento del outbox (§2.9).
 *
 * <p>El dueño del agregado lo escucha para actualizar su estado. En F12, el servicio de incidencias
 * pasa la incidencia de {@code PENDIENTE_REPORTE} a {@code EN_CUARENTENA} y guarda
 * {@code referenciaExterna} como su {@code incidenciaInventoryId} (§5.12).</p>
 */
public record EventoOutboxEnviado(TipoEventoOutbox tipo, String agregadoId, String referenciaExterna) {
}

package pe.edu.unmsm.fisi.retail.service.outbox;

/**
 * Se publica dentro de {@code ms-retail} cuando un evento del outbox pasa a {@code FALLIDO} (§2.9):
 * el destino lo rechazó de forma definitiva o se agotaron los intentos.
 */
public record EventoOutboxFallido(TipoEventoOutbox tipo, String agregadoId, int intentos, String motivo) {
}

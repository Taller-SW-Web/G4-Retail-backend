package pe.edu.unmsm.fisi.retail.dto;

/**
 * Constancia digital de la entrega física en tienda (RF-19).
 *
 * @param constanciaId     número de la constancia
 * @param fechaHoraEntrega instante de la entrega en ISO-8601
 * @param estado           siempre {@code ENTREGADO}
 */
public record EntregaPickupResponse(String constanciaId, String fechaHoraEntrega, String estado) {
}

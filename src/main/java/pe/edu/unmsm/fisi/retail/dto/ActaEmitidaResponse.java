package pe.edu.unmsm.fisi.retail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Respuesta {@code 201} del acta de merma (F12, RF-34, contrato 5.12). Trae todo lo que el POS imprime:
 * Retail no genera PDF.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ActaEmitidaResponse(
        String actaNumero,
        String fechaEmision,
        String tiendaId,
        String tipoDestino,
        String observaciones,
        Supervisor supervisor,
        int totalPrendas,
        List<Prenda> incidencias) {

    public record Supervisor(String id, String nombre) {}

    public record Prenda(String incidenciaId, String sku, String tipoFalla, String detalleObservacion, String estado) {}
}

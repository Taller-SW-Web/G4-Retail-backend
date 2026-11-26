package pe.edu.unmsm.fisi.retail.integracion.inventario;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Cuerpo de {@code POST /api/v1/inventario/incidencias} de Inventory (G6), según
 * {@code CrearIncidenciaInventarioRequest} de su OpenAPI (contrato de Retail v0.5, §5.12).
 *
 * <p>El servicio de F12 lo arma con {@link #desdeRetail} al registrar la incidencia y lo deja en el
 * outbox con {@code TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA}.</p>
 *
 * @param operationId        UUID de la operación; Retail usa el mismo {@code incidenciaId}
 * @param externalIncidentId id de la incidencia en Retail ({@code incidenciaId})
 * @param quantity           unidades afectadas; en el mostrador se reporta prenda por prenda (1)
 * @param locationId         ubicación de Inventory que corresponde a la tienda (🟡 pendiente de G6)
 * @param type               {@code DANIO}, {@code NO_UBICADA} u {@code OTRO_FISICO}
 * @param occurredAt         instante del reporte
 * @param actorRef           referencia opaca al vendedor que reportó
 */
public record IncidenciaReportadaPayload(
        @JsonProperty("operation_id") String operationId,
        @JsonProperty("external_incident_id") String externalIncidentId,
        @JsonProperty("sku") String sku,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("location_id") String locationId,
        @JsonProperty("type") String type,
        @JsonProperty("occurred_at") OffsetDateTime occurredAt,
        @JsonProperty("actor_ref") String actorRef) {

    /**
     * Arma el cuerpo de G6 a partir de la incidencia de Retail.
     *
     * @param incidenciaId UUID local de la incidencia (también es la {@code Idempotency-Key})
     * @param tipoFalla    tipo de falla de Retail (§5.12)
     */
    public static IncidenciaReportadaPayload desdeRetail(UUID incidenciaId, String locationId, String sku,
                                                         String tipoFalla, OffsetDateTime reportadaEn, UUID vendedorId) {
        return new IncidenciaReportadaPayload(incidenciaId.toString(), incidenciaId.toString(), sku, 1,
                locationId, tipoInventory(tipoFalla), reportadaEn, vendedorId == null ? null : vendedorId.toString());
    }

    /**
     * Tipo de falla de Retail → tipo de incidencia de Inventory.
     * {@code EXTRAVIO_NO_UBICADO} → {@code NO_UBICADA}; las fallas físicas de la prenda → {@code DANIO};
     * cualquier otro valor → {@code OTRO_FISICO}.
     */
    public static String tipoInventory(String tipoFalla) {
        if (tipoFalla == null) {
            return "OTRO_FISICO";
        }
        return switch (tipoFalla) {
            case "EXTRAVIO_NO_UBICADO" -> "NO_UBICADA";
            case "MANCHADO_PROBADOR", "COSTURA_ROTA", "DEFECTO_FABRICA" -> "DANIO";
            default -> "OTRO_FISICO";
        };
    }
}

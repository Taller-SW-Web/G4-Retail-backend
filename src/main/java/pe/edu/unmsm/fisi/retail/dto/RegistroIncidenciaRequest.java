package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.edu.unmsm.fisi.retail.model.TipoFalla;

/**
 * Cuerpo de {@code POST /api/v1/retail/inventario/incidencias} (contrato §5.12, RF-32).
 *
 * @param sku                SKU de la prenda afectada
 * @param codigoBarras       código leído por el escáner; opcional si la prenda se buscó por SKU
 * @param tipoFalla          motivo de la incidencia (CA-RF32-04: obligatorio)
 * @param detalleObservacion descripción del daño o del extravío, al menos 5 caracteres
 * @param evidenciaFotoUrl   URL opcional de la foto de evidencia
 */
public record RegistroIncidenciaRequest(
        @NotBlank(message = "El SKU es obligatorio")
        @Size(max = 100, message = "El SKU admite hasta 100 caracteres")
        String sku,

        @Size(max = 50, message = "El código de barras admite hasta 50 caracteres")
        String codigoBarras,

        @NotNull(message = "Selecciona el tipo de falla")
        TipoFalla tipoFalla,

        @NotBlank(message = "La observación es obligatoria")
        @Size(min = 5, max = 1000, message = "La observación debe tener entre 5 y 1000 caracteres")
        String detalleObservacion,

        @Size(max = 255, message = "La URL de la foto admite hasta 255 caracteres")
        String evidenciaFotoUrl) {
}

package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import pe.edu.unmsm.fisi.retail.model.MotivoCambio;

import java.util.UUID;

/**
 * Cuerpo de {@code POST /api/v1/retail/cambios} (F9, RF-24 y RF-25).
 *
 * @param boletaNumero comprobante con que se compro la prenda: serie y correlativo de 8 digitos
 * @param sku          prenda que el cliente devuelve
 * @param cantidad     unidades que cambia
 * @param motivo       talla, modelo o falla de fabrica
 * @param inspeccion   checklist del estado fisico de la prenda
 * @param clienteId    cliente identificado en mostrador; opcional
 */
public record RegistroCambioRequest(
        @NotBlank(message = "Indica el numero de boleta")
        @Pattern(regexp = BoletaNumero.PATRON, message = BoletaNumero.MENSAJE)
        String boletaNumero,

        @NotBlank(message = "Indica el SKU de la prenda que se devuelve")
        String sku,

        @Min(value = 1, message = "La cantidad debe ser al menos 1")
        @Max(value = 99, message = "La cantidad no puede superar 99")
        int cantidad,

        @NotNull(message = "Indica el motivo del cambio")
        MotivoCambio motivo,

        @NotNull(message = "Falta el checklist de inspeccion")
        @Valid
        InspeccionPrenda inspeccion,

        UUID clienteId) {
}

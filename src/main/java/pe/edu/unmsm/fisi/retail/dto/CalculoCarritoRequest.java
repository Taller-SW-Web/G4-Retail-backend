package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Solicitud de calculo oficial y vista previa financiera del carrito POS (F4).
 *
 * <pre>
 * POST /api/v1/retail/carritos/calcular
 * </pre>
 *
 * <p>Recibe los SKUs agregados en mostrador y un cupon comercial opcional.
 * El backend valida y consolida el calculo tributario segun normativa SUNAT (RF-11, RF-12).</p>
 */
public record CalculoCarritoRequest(

        @NotEmpty(message = "El carrito debe contener al menos un item")
        @Valid
        List<ItemCarritoCalculoDto> items,

        String cupon

) {

    /**
     * Variante o prenda enviada desde el mostrador.
     */
    public record ItemCarritoCalculoDto(

            @NotBlank(message = "El sku es obligatorio")
            String sku,

            @NotNull(message = "La cantidad es obligatoria")
            @Min(value = 1, message = "La cantidad debe ser mayor a 0")
            Integer cantidad,

            BigDecimal precioUnitario

    ) {
    }
}

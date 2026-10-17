package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO para la suspension y recuperacion de carritos en espera (Held Carts / Probadores) (RF-10).
 *
 * <pre>
 * POST /api/v1/retail/carritos-espera
 * </pre>
 */
public record CarritoEsperaDto(

        UUID carritoEsperaId,

        @NotBlank(message = "El aliasTicket es obligatorio")
        String aliasTicket,

        UUID clienteId,

        @NotEmpty(message = "El carrito en espera debe contener al menos un item")
        @Valid
        List<ItemCarritoEsperaDto> items,

        BigDecimal subtotalEstimado,
        OffsetDateTime fechaCreacion,
        OffsetDateTime fechaExpiracion,
        String estado

) {

    /**
     * Linea de prenda en espera.
     */
    public record ItemCarritoEsperaDto(

            @NotBlank(message = "El sku es obligatorio")
            String sku,

            String productoId,
            String nombreProducto,
            String tallaColor,

            @NotNull(message = "La cantidad es obligatoria")
            @Positive(message = "La cantidad debe ser mayor a 0")
            Integer cantidad,

            @NotNull(message = "El precio unitario es obligatorio")
            BigDecimal precioUnitario

    ) {
    }
}

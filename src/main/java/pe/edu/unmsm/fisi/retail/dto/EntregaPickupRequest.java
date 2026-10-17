package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /api/v1/retail/pedidos/{pedidoId}/entrega} (RF-19).
 *
 * <p>Solo trae lo que el vendedor verifica en mostrador. La tienda y el encargado de la entrega no
 * viajan aquí: el backend los toma del token. El documento de reparto de la Semana 8 lo lista como
 * {@code EntregaPickupRequest}.</p>
 *
 * @param dniRecoge           DNI de quien retira el paquete (8 dígitos)
 * @param nombreRecoge        nombres completos de quien retira
 * @param esTerceroAutorizado {@code true} si no es el titular de la compra
 */
public record EntregaPickupRequest(

        @NotBlank(message = "El DNI de quien recoge es obligatorio")
        @Pattern(regexp = "^\\d{8}$", message = "El DNI debe tener exactamente 8 dígitos")
        String dniRecoge,

        @NotBlank(message = "El nombre de quien recoge es obligatorio")
        @Size(max = 120, message = "El nombre no puede superar los 120 caracteres")
        String nombreRecoge,

        boolean esTerceroAutorizado) {
}

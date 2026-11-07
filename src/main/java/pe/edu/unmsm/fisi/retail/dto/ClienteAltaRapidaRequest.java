package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Alta rapida del cliente que no figura en el padron (F3, RF-08).
 *
 * <pre>
 * POST /api/v1/retail/clientes
 * </pre>
 *
 * <p>Solo lleva datos de identificacion. El par {@code tipoDocumento}/{@code numeroDocumento} no se
 * valida con anotaciones porque su formato depende del tipo (DNI, RUC, CE o PASAPORTE): lo valida
 * {@code ValidadorDocumento} en el servicio y, si falla, responde {@code 400 DATO_INVALIDO} (RF-09).</p>
 */
public record ClienteAltaRapidaRequest(

        String canalOrigen,

        String tipoDocumento,

        String numeroDocumento,

        @NotBlank(message = "El campo nombres es obligatorio")
        String nombres,

        @NotBlank(message = "El campo apellidos es obligatorio")
        String apellidos,

        /** Opcional; si llega, debe tener arroba y un dominio con punto (RF-09). */
        @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$",
                message = "El campo email no tiene un formato de correo valido")
        String email,

        String telefono

) {
}

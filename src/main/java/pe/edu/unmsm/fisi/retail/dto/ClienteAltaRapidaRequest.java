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
 * <p>Solo lleva datos de identificacion. El numero de documento se valida a 8 digitos numericos
 * antes de registrar al cliente, para no dar de alta documentos con forma invalida.</p>
 */
public record ClienteAltaRapidaRequest(

        String canalOrigen,

        String tipoDocumento,

        @NotBlank(message = "El campo numeroDocumento es obligatorio")
        @Pattern(regexp = "\\d{8}", message = "El campo numeroDocumento debe tener exactamente 8 digitos numericos")
        String numeroDocumento,

        @NotBlank(message = "El campo nombres es obligatorio")
        String nombres,

        @NotBlank(message = "El campo apellidos es obligatorio")
        String apellidos,

        String email,

        String telefono

) {
}

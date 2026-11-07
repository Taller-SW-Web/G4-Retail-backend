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

        /**
         * Opcional; si llega, se valida su sintaxis (RF-09). La parte local no admite puntos
         * consecutivos ni al inicio o al final. El dominio son etiquetas alfanumericas (con guion
         * interno) separadas por un solo punto, y termina en un TLD de al menos 2 letras. Asi se
         * rechazan {@code rosa@correo..com}, {@code rosa@.correo.com} o {@code rosa@correo.c}.
         */
        @Pattern(regexp = ClienteAltaRapidaRequest.PATRON_EMAIL,
                message = "El campo email no tiene un formato de correo valido")
        String email,

        String telefono

) {

    /** Sintaxis de correo aceptada en el alta rapida; la SPA usa la misma en {@code lib/documento.ts}. */
    public static final String PATRON_EMAIL =
            "^[^\\s@.]+(\\.[^\\s@.]+)*@([A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,}$";
}

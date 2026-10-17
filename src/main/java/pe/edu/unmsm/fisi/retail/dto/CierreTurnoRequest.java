package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Cuerpo de {@code POST /api/v1/retail/caja/turnos/{turnoId}/cierre} (RF-22).
 *
 * <p>Es el arqueo ciego: el cajero cuenta billetes y monedas y declara el total <strong>sin haber
 * visto</strong> lo que el sistema esperaba. Por eso el cuerpo solo trae lo que el cajero digitó; el
 * saldo del sistema lo calcula el backend y no se le anticipa a la SPA.</p>
 *
 * <p>El documento de reparto de la Semana 8 lo lista como {@code CierreTurnoRequest}.</p>
 *
 * @param montoDeclarado total contado físicamente en la gaveta
 * @param observaciones  nota libre del cajero; se guarda con el cierre
 */
public record CierreTurnoRequest(

        @NotNull(message = "El monto declarado es obligatorio")
        @DecimalMin(value = "0.00", message = "El monto declarado no puede ser negativo")
        BigDecimal montoDeclarado,

        @Size(max = 300, message = "Las observaciones no pueden superar los 300 caracteres")
        String observaciones) {
}

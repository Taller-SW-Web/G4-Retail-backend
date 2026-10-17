package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.edu.unmsm.fisi.retail.model.TipoMovimientoCaja;

import java.math.BigDecimal;

/**
 * Cuerpo de {@code POST /api/v1/retail/caja/turnos/{turnoId}/movimientos}: ingreso o egreso menor de
 * caja chica (RF-21).
 *
 * <p>{@code autorizadoPor} es texto libre, tal como se digita en el mostrador
 * ({@code "Supervisor Juan"}): el cajero registra el gasto y anota quién lo aprobó. La tabla
 * {@code RET_MOVIMIENTO_CAJA} guarda ese dato en {@code autorizado_por_supervisor}, que es
 * {@code NOT NULL} porque todo egreso tiene que quedar con responsable.</p>
 *
 * <p>El documento de reparto lista los DTOs de turno, pero no uno para movimientos; se añade este
 * porque RF-21 no se puede cumplir sin registrar los movimientos.</p>
 *
 * @param tipoMovimiento {@code INGRESO_MENOR} o {@code SALIDA_GASTO}
 * @param monto          importe, siempre positivo; el signo lo da el tipo
 * @param motivo         justificación del movimiento
 * @param autorizadoPor  nombre o código de quien autoriza
 */
public record MovimientoTurnoRequest(

        @NotNull(message = "El tipo de movimiento es obligatorio")
        TipoMovimientoCaja tipoMovimiento,

        @NotNull(message = "El monto es obligatorio")
        @DecimalMin(value = "0.01", message = "El monto debe ser mayor que cero")
        BigDecimal monto,

        @NotBlank(message = "El motivo es obligatorio")
        @Size(max = 300, message = "El motivo no puede superar los 300 caracteres")
        String motivo,

        @NotBlank(message = "La autorización del supervisor es obligatoria")
        @Size(max = 100, message = "La autorización no puede superar los 100 caracteres")
        String autorizadoPor) {
}

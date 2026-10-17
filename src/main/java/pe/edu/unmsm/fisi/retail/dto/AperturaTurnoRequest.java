package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Cuerpo de {@code POST /api/v1/retail/caja/turnos}: apertura del turno con fondo fijo (RF-20).
 *
 * <p>Forma tomada del documento de reparto de la Semana 8, que lista este DTO como
 * {@code AperturaTurnoRequest}.</p>
 *
 * @param terminalPos          terminal que abre el turno, por ejemplo {@code POS-01}
 * @param saldoInicialEfectivo fondo fijo inicial; la tabla exige que no sea negativo
 */
public record AperturaTurnoRequest(

        @NotBlank(message = "La terminal es obligatoria")
        @Size(max = 20, message = "La terminal no puede superar los 20 caracteres")
        String terminalPos,

        @NotNull(message = "El fondo fijo es obligatorio")
        @DecimalMin(value = "0.00", message = "El fondo fijo no puede ser negativo")
        BigDecimal saldoInicialEfectivo) {
}

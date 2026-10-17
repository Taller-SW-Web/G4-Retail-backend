package pe.edu.unmsm.fisi.retail.dto;

import pe.edu.unmsm.fisi.retail.model.CajaMovimiento;

import java.math.BigDecimal;

/**
 * Respuesta de {@code POST /api/v1/retail/caja/turnos/{turnoId}/movimientos} con
 * {@code 201 Created} (RF-21).
 *
 * <p>Devuelve el saldo estimado en gaveta después del movimiento, que es lo que la pantalla muestra
 * para que el cajero confirme que el egreso se registró. No es el saldo del arqueo: los cobros de
 * venta en efectivo entran por F5 y todavía no se suman aquí (ver {@code CajaService}).</p>
 *
 * @param movimientoId       identificador del movimiento registrado
 * @param nuevoSaldoEstimado efectivo disponible en gaveta tras aplicar el movimiento
 */
public record MovimientoTurnoResponse(
        String movimientoId,
        BigDecimal nuevoSaldoEstimado) {

    public static MovimientoTurnoResponse desde(CajaMovimiento movimiento, BigDecimal nuevoSaldoEstimado) {
        return new MovimientoTurnoResponse(movimiento.getIdMovimiento().toString(), nuevoSaldoEstimado);
    }
}

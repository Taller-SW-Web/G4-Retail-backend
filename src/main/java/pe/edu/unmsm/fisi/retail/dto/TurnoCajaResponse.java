package pe.edu.unmsm.fisi.retail.dto;

import pe.edu.unmsm.fisi.retail.model.CajaSesion;

import java.math.BigDecimal;

/**
 * Estado del turno de caja (RF-20).
 *
 * <p>Es la respuesta de {@code POST /api/v1/retail/caja/turnos} (apertura) y de
 * {@code GET /api/v1/retail/caja/turnos/actual} (consulta), porque ambas devuelven lo mismo: el turno
 * vigente. El documento de reparto de la Semana 8 lo lista como {@code TurnoCajaResponse}.</p>
 *
 * <p>Cuando la tienda no tiene turno abierto se responde {@code estado = CERRADA} y el resto de
 * campos no viajan en el JSON ({@code spring.jackson.default-property-inclusion=non_null}). Así la
 * SPA distingue "no hay turno" de "error" sin mirar el código HTTP.</p>
 *
 * @param turnoId              identificador del turno ({@code id_sesion} en {@code RET_CAJA_SESION})
 * @param estado               {@code ABIERTA} o {@code CERRADA}
 * @param terminalPos          terminal del turno
 * @param cajero               código visible de quien abrió el turno, por ejemplo {@code VEN-001}
 * @param fechaHoraApertura    instante de apertura en ISO-8601
 * @param saldoInicialEfectivo fondo fijo con el que arrancó la gaveta
 */
public record TurnoCajaResponse(
        String turnoId,
        String estado,
        String terminalPos,
        String cajero,
        String fechaHoraApertura,
        BigDecimal saldoInicialEfectivo) {

    /** Respuesta cuando la tienda no tiene ningún turno abierto. */
    public static TurnoCajaResponse sinTurno() {
        return new TurnoCajaResponse(null, "CERRADA", null, null, null, null);
    }

    /**
     * Construye la respuesta a partir del turno.
     *
     * @param sesion turno vigente
     * @param cajero código visible de quien lo abrió, o {@code null} si ya no es personal activo
     */
    public static TurnoCajaResponse desde(CajaSesion sesion, String cajero) {
        return new TurnoCajaResponse(
                sesion.getIdSesion().toString(),
                sesion.getEstado().name(),
                sesion.getTerminalPosCodigo(),
                cajero,
                sesion.getFechaHoraApertura().toString(),
                sesion.getSaldoInicialEfectivo());
    }
}

package pe.edu.unmsm.fisi.retail.integracion.boletas;

import java.util.Optional;

/**
 * Consulta de las boletas emitidas en mostrador, para validar un cambio de prenda (F9, RF-23).
 *
 * <p>Se definio como interfaz porque la persistencia de las ventas (F5) todavia no existe: hoy el
 * {@code VentaService} guarda las boletas en memoria. El PO confirmo que las ventas pasaran a
 * persistirse en {@code RET_VENTA}; cuando eso ocurra, se agrega un adaptador JPA que implemente esta
 * interfaz y F9 no cambia.</p>
 */
public interface BoletasEmitidasPort {

    /** Busca una boleta por su numero exacto, por ejemplo {@code B001-00000045}. */
    Optional<BoletaEmitida> buscarPorNumero(String numero);
}

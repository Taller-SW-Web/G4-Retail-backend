package pe.edu.unmsm.fisi.retail.model;

/**
 * Destino de las prendas de un acta de merma (F12, RF-34, contrato 5.12).
 *
 * <p>Cada destino define el estado en que queda la incidencia (decision del PO, 26/11): solo la
 * devolucion viaja al almacen central; la baja y el ajuste por perdida no vuelven a la venta.</p>
 */
public enum TipoDestinoActa {
    DEVOLUCION_CENTRAL("DERIVADO_ALMACEN"),
    BAJA_DEFINITIVA("BAJA_DEFINITIVA"),
    /** La prenda extraviada no viaja: Inventory registra el faltante al resolver la incidencia. */
    AJUSTE_PERDIDA("BAJA_DEFINITIVA");

    private final String estadoIncidencia;

    TipoDestinoActa(String estadoIncidencia) {
        this.estadoIncidencia = estadoIncidencia;
    }

    /** Estado en que queda cada incidencia del acta. */
    public String estadoIncidencia() {
        return estadoIncidencia;
    }
}

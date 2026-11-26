package pe.edu.unmsm.fisi.retail.service.outbox;

/**
 * Tipos de evento que viajan por el outbox y el módulo al que van (contrato v0.5, §2.9).
 */
public enum TipoEventoOutbox {

    /** F12 (RF-34, tarea B6): incidencia de inventario reportada a Inventory de Productos (G6). */
    INCIDENCIA_INVENTARIO_REPORTADA(DestinoOutbox.PRODUCTOS_G6);

    private final String destino;

    TipoEventoOutbox(String destino) {
        this.destino = destino;
    }

    public String destino() {
        return destino;
    }
}

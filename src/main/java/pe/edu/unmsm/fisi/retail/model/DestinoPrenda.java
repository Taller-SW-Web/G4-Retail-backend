package pe.edu.unmsm.fisi.retail.model;

/**
 * Destino que se sugiere para una prenda recibida en un cambio aprobado (F9, RF-24).
 *
 * <p>Es una <strong>sugerencia</strong> que queda registrada, no un movimiento de stock: Retail no
 * incrementa ni descuenta saldos de inventario (decision G4-P0-03 del contrato).</p>
 */
public enum DestinoPrenda {

    /** La prenda volvio intacta y puede venderse de nuevo. */
    REINGRESO_INVENTARIO,

    /** La prenda tiene un defecto: va a merma o a reclamo de garantia, no a la venta. */
    MERMA_GARANTIA
}

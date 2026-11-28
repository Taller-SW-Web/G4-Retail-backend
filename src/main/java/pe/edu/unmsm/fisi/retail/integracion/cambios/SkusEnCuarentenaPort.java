package pe.edu.unmsm.fisi.retail.integracion.cambios;

/**
 * Si un SKU tiene cuarentena activa en la tienda (F12, RF-33): las prendas nuevas de un cambio no
 * pueden estar en cuarentena (contrato 5.9, {@code 422 SKU_EN_CUARENTENA}).
 *
 * <p>La implementa {@link pe.edu.unmsm.fisi.retail.service.SkusEnCuarentenaF12} sobre las
 * incidencias de F12.</p>
 */
public interface SkusEnCuarentenaPort {

    boolean enCuarentena(String tiendaId, String sku);
}

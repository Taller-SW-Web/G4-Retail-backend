package pe.edu.unmsm.fisi.retail.integracion.cambios;

/**
 * Si un SKU tiene cuarentena activa en la tienda (F12, RF-33): las prendas nuevas de un cambio no
 * pueden estar en cuarentena (contrato 5.9, {@code 422 SKU_EN_CUARENTENA}).
 *
 * <p>La cuarentena es de F12 (Angie). Mientras su servicio no este en develop, rige
 * {@link SinCuarentenaRegistrada}; cuando lo publique, basta con que su componente implemente este
 * puerto.</p>
 */
public interface SkusEnCuarentenaPort {

    boolean enCuarentena(String tiendaId, String sku);
}

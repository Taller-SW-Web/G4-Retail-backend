package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;

/**
 * Variante vendible de un articulo, tal como la consume el mostrador (RF-05 y RF-06).
 *
 * <p>Es la combinacion concreta de talla y color que el vendedor elige en la matriz del modal, y la
 * unica unidad que puede entrar al carrito: el producto, por si solo, no se vende.</p>
 *
 * <p>Forma fijada por la seccion 5.2 del contrato de API v0.4, mas el precio por SKU: G6 cotiza por
 * variante ({@code GET /precios/skus/{sku}}), no por producto, y dos tallas del mismo modelo pueden
 * costar distinto.</p>
 *
 * @param sku            SKU vendible, por ejemplo {@code ZAPVEL-40-AZU}
 * @param talla          talla de la variante, textual o numerica
 * @param color          color de la variante
 * @param precio         precio vigente del SKU, ya con la oferta aplicada si la hubiera
 * @param disponibilidad estado operativo en mostrador, en el vocabulario de Retail
 * @param stockTienda    unidades fisicas en la tienda; <strong>puede ser nulo</strong>, ver nota
 * @param enCuarentena   si es true la SPA bloquea agregarla al carrito (RF-33)
 */
public record VarianteResponse(
        String sku,
        String talla,
        String color,
        BigDecimal precio,
        Disponibilidad disponibilidad,
        Integer stockTienda,
        boolean enCuarentena) {

    /**
     * Estados de disponibilidad del contrato de Retail (RF-06).
     *
     * <p>No son los de G6: la traduccion entre ambos vocabularios vive en
     * {@code CatalogoService} y esta documentada en {@code docs/integracion-productos-g6.md}.</p>
     */
    public enum Disponibilidad {
        DISPONIBLE,
        ULTIMAS_UNIDADES,
        SOLO_ALMACEN_CENTRAL,
        AGOTADO_TOTAL
    }

    /**
     * Si la variante se puede vender en mostrador ahora mismo.
     *
     * <p>Un articulo en cuarentena no se vende aunque haya existencias, y uno agotado en toda la
     * cadena tampoco. {@code SOLO_ALMACEN_CENTRAL} queda fuera a proposito: hay unidades, pero no
     * en esta tienda, y la venta presencial inmediata necesita la prenda en mano (RF-06).</p>
     */
    public boolean vendibleEnMostrador() {
        return !enCuarentena
                && disponibilidad != Disponibilidad.AGOTADO_TOTAL
                && disponibilidad != Disponibilidad.SOLO_ALMACEN_CENTRAL;
    }
}

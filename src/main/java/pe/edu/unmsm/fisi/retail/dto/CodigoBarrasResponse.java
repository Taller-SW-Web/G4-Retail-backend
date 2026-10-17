package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;

/**
 * Respuesta de la lectura por codigo de barras (RF-04), seccion 5.2 del contrato v0.4.
 *
 * <p>Devuelve <strong>una sola variante</strong>, no el producto entero con todas sus tallas: el
 * codigo de barras identifica la prenda fisica que el vendedor tiene en la mano, y abrir la matriz
 * de variantes despues de escanear obligaria a elegir de nuevo lo que ya esta decidido.</p>
 *
 * @param codigoBarras codigo leido, tal como llego
 * @param productoId   producto al que pertenece la variante
 * @param nombre       nombre comercial del articulo
 * @param precio       precio vigente de esa variante
 * @param variante     la variante exacta que resuelve el codigo
 */
public record CodigoBarrasResponse(
        String codigoBarras,
        String productoId,
        String nombre,
        BigDecimal precio,
        VarianteResponse variante) {
}

package pe.edu.unmsm.fisi.retail.integracion.productos;

import pe.edu.unmsm.fisi.retail.integracion.productos.dto.DisponibilidadComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.FiltroCatalogo;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.PaginaProductos;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.Precio;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;

import java.util.List;
import java.util.Optional;

/**
 * Puerto hacia el modulo Productos y Ofertas (G6).
 *
 * <p>Las firmas reproducen el OpenAPI que publica G6 en
 * {@code G6-Productos-Ofertas-docs/contratos/http/openapi.yaml}. Hay dos implementaciones
 * intercambiables por perfil de Spring: el mock de la Semana 8 y el adaptador HTTP real. Pasar de
 * una a otra debe ser solo configuracion (seccion 6 del contrato de API v0.4).</p>
 *
 * <p><strong>Retail solo lee.</strong> Las operaciones de inventario que modifican saldos estan
 * prohibidas para este modulo: el stock lo orquesta Ventas (G4-P0-01 y G4-P0-05).</p>
 */
public interface ProductosPort {

    /**
     * Catalogo comercial paginado.
     *
     * <p>G6: {@code GET /productos}. Ojo: su paginacion empieza en <strong>1</strong>, mientras que
     * el contrato de Retail expone {@code page} empezando en 0; la conversion se hace aqui.</p>
     */
    PaginaProductos listarProductos(FiltroCatalogo filtro);

    /**
     * Variantes vendibles de un producto, que es de donde salen talla, color y SKU.
     *
     * <p>G6: {@code GET /productos/{productoId}/variantes}. Son una llamada aparte del catalogo.</p>
     */
    List<VarianteComercial> listarVariantes(String productoId);

    /**
     * Traduce un codigo de barras a un SKU vendible.
     *
     * <p>G6: {@code POST /productos/codigos-barras/resolver}, que recibe {@code codigo_barras} y
     * responde <strong>solo el sku</strong>. No se debe asumir que el codigo de barras es el SKU.</p>
     *
     * @return el SKU, o vacio si el codigo no corresponde a ningun producto
     */
    Optional<String> resolverCodigoBarras(String codigoBarras);

    /**
     * Precio vigente de un SKU.
     *
     * <p>G6: {@code GET /precios/skus/{sku}}. El catalogo no trae precios, por eso la pantalla de
     * venta necesita esta segunda llamada.</p>
     */
    Optional<Precio> consultarPrecio(String sku);

    /**
     * Disponibilidad comercial, sin cantidades.
     *
     * <p>G6: {@code GET /inventario/disponibilidad}. Productos no expone saldos a los canales
     * (X-P0-03), asi que esto devuelve un estado, nunca un numero de unidades.</p>
     */
    List<DisponibilidadComercial> consultarDisponibilidad(List<String> skus);
}

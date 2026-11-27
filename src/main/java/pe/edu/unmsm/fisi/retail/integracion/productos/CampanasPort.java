package pe.edu.unmsm.fisi.retail.integracion.productos;

import pe.edu.unmsm.fisi.retail.integracion.productos.dto.CampanaComercial;

import java.util.List;

/**
 * Campañas y promociones publicadas por Productos y Ofertas (G6), para la torre de control (F11, RF-31).
 *
 * <p>G6 todavía no confirma la ruta de "promociones vigentes del canal" (contrato v0.5, §4.2 y §7.1):
 * mientras tanto la atiende un doble. El servicio de Retail filtra por canal {@code RETAIL} y por
 * vigencia, así que el adaptador real solo tiene que devolver lo que G6 publique.</p>
 */
public interface CampanasPort {

    /** Campañas publicadas, de cualquier canal y vigencia. */
    List<CampanaComercial> listarCampanas();

    /** Fuente de datos, para los logs: {@code PRODUCTOS_G6} o {@code EN_MEMORIA}. */
    String origen();
}

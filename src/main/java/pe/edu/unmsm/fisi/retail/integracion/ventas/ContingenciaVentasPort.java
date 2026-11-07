package pe.edu.unmsm.fisi.retail.integracion.ventas;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest.VentaOffline;
public interface ContingenciaVentasPort {
    String registrar(VentaOffline venta,String tienda,String clave,String comprobante);
}

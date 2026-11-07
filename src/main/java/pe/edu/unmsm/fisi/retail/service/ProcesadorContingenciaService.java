package pe.edu.unmsm.fisi.retail.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import pe.edu.unmsm.fisi.retail.dto.*;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest.VentaOffline;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineResponse.Resultado;
import pe.edu.unmsm.fisi.retail.repository.*;
import pe.edu.unmsm.fisi.retail.model.EstadoCobroTurno;
import pe.edu.unmsm.fisi.retail.integracion.ventas.ContingenciaVentasPort;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class ProcesadorContingenciaService {
    private final ContingenciaLogRepository logs;
    private final CajaService caja;
    private final ContingenciaVentasPort ventas;

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Resultado procesar(SincronizacionOfflineRequest lote,VentaOffline venta,UUID usuario) {
        var log=logs.bloquear(venta.ventaLocalUuid().toString()).orElseThrow();
        ReservaContingenciaService.comprobar(log,lote,venta,usuario);
        var comprobante="B010-"+String.format("%08d",log.getNumeroComprobante());
        if("RESINCRONIZADO".equals(log.getEstado()))
            return new Resultado(venta.ventaLocalUuid(),"DUPLICADO",log.getPedidoId(),comprobante,null);
        caja.validarTurnoParaCobro(usuario,lote.turnoId(),lote.terminalPos());
        var pedido=ventas.registrar(venta,log.getTiendaId(),log.getVentaLocalUuid(),comprobante);
        if(pedido==null || pedido.isBlank()) throw new IllegalStateException("G5 no confirmó el pedido");
        caja.registrarCobro(usuario,lote.turnoId(),lote.terminalPos(),new RegistroCobroRequest(
            log.getVentaLocalUuid(),"EFECTIVO",venta.totalNeto(),venta.efectivoRecibido(),pedido,"B010",
            String.format("%08d",log.getNumeroComprobante())));
        caja.actualizarEstadoCobro(usuario,log.getVentaLocalUuid(),EstadoCobroTurno.CONFIRMADO,pedido);
        log.setPedidoId(pedido); log.setEstado("RESINCRONIZADO"); log.setFechaSincronizacion(OffsetDateTime.now());
        logs.saveAndFlush(log);
        return new Resultado(venta.ventaLocalUuid(),"RESINCRONIZADO",pedido,comprobante,null);
    }
}

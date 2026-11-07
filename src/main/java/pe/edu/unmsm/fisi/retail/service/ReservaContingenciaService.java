package pe.edu.unmsm.fisi.retail.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import pe.edu.unmsm.fisi.retail.dto.*;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest.VentaOffline;
import pe.edu.unmsm.fisi.retail.model.*;
import pe.edu.unmsm.fisi.retail.repository.*;
import pe.edu.unmsm.fisi.retail.exception.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class ReservaContingenciaService {
    private final AuthService auth;
    private final CajaService caja;
    private final ContingenciaLogRepository logs;
    private final EntityManager entidades;
    private final JdbcTemplate sql;
    private final ObjectMapper json;

    /** Se confirma antes de G5: una respuesta perdida nunca cambia comprobante ni UUID. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reservar(SincronizacionOfflineRequest lote, VentaOffline venta, UUID usuario) {
        var perfil=auth.obtenerPerfil(usuario);
        var turno=entidades.find(CajaSesion.class,lote.turnoId(),LockModeType.PESSIMISTIC_WRITE);
        if(turno==null) throw new ReglaNegocioException("TURNO_NO_ENCONTRADO","No existe el turno");
        if(!turno.getTiendaId().equals(perfil.tiendaId()) || !turno.getTerminalPosCodigo().equals(lote.terminalPos()))
            throw new AccesoDenegadoException("TURNO_AJENO","El turno no corresponde a la tienda y terminal");
        var anterior=logs.findByVentaLocalUuid(venta.ventaLocalUuid().toString());
        if(anterior.isPresent()) {
            comprobar(anterior.get(),lote,venta,usuario);
            return;
        }
        caja.validarTurnoParaCobro(usuario,lote.turnoId(),lote.terminalPos());
        var fecha=OffsetDateTime.parse(venta.fechaHoraOffline());
        if(fecha.isBefore(turno.getFechaHoraApertura()) || fecha.isAfter(OffsetDateTime.now().plusMinutes(5)))
            throw new ReglaNegocioException("FECHA_OFFLINE_INVALIDA","La fecha no pertenece al turno");
        var log=new ContingenciaLog();
        log.setId(UUID.randomUUID()); log.setTiendaId(perfil.tiendaId()); log.setTerminalPos(lote.terminalPos());
        log.setVentaLocalUuid(venta.ventaLocalUuid().toString()); log.setHash(venta.hashIntegridad());
        log.setTurnoId(lote.turnoId()); log.setVendedorId(usuario); log.setEstado("PENDIENTE");
        log.setFechaOffline(fecha);
        log.setNumeroComprobante(sql.queryForObject("select nextval('ret_contingencia_comprobante_seq')",Long.class));
        try { log.setPayload(json.writeValueAsString(venta)); }
        catch(Exception e) { throw new IllegalArgumentException("Venta no serializable",e); }
        logs.saveAndFlush(log);
    }
    static void comprobar(ContingenciaLog log,SincronizacionOfflineRequest lote,VentaOffline venta,UUID usuario) {
        if(!venta.hashIntegridad().equals(log.getHash()) || !usuario.equals(log.getVendedorId())
            || !lote.turnoId().equals(log.getTurnoId()) || !lote.terminalPos().equals(log.getTerminalPos()))
            throw new ReglaNegocioException("IDEMPOTENCY_CONFLICT","El UUID ya pertenece a otra venta");
    }
}

package pe.edu.unmsm.fisi.retail.service;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.unmsm.fisi.retail.dto.*;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest.VentaOffline;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineResponse.Resultado;
import pe.edu.unmsm.fisi.retail.exception.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class SincronizacionOfflineService {
    private final ReservaContingenciaService reservas;
    private final ProcesadorContingenciaService procesador;
    private final HashContingencia hash;
    private final Validator validador;
    private final AuthService auth;

    public SincronizacionOfflineResponse sincronizar(SincronizacionOfflineRequest lote, UUID usuario) {
        auth.obtenerPerfil(usuario);
        var resultados=new ArrayList<Resultado>();
        for(var venta:lote.lote()) resultados.add(sincronizarUna(lote,venta,usuario));
        return new SincronizacionOfflineResponse(resultados.size(),resultados);
    }
    private Resultado sincronizarUna(SincronizacionOfflineRequest lote,VentaOffline v,UUID usuario) {
        try {
            if(v==null || !validador.validate(v).isEmpty())
                return error(v,"RECHAZADO","VENTA_OFFLINE_INVALIDA");
            if(!hash.calcular(lote,v).equals(v.hashIntegridad())) return error(v,"RECHAZADO","HASH_INVALIDO");
            OffsetDateTime.parse(v.fechaHoraOffline());
            var total=v.items().stream().map(i -> i.precioUnitario().multiply(BigDecimal.valueOf(i.cantidad())))
                .reduce(BigDecimal.ZERO,BigDecimal::add);
            if(total.compareTo(v.totalNeto())!=0 || v.efectivoRecibido().subtract(total).compareTo(v.vuelto())!=0)
                return error(v,"RECHAZADO","IMPORTES_INVALIDOS");
            reservas.reservar(lote,v,usuario);
            return procesador.procesar(lote,v,usuario);
        } catch(ReglaNegocioException e) {
            return error(v,"IDEMPOTENCY_CONFLICT".equals(e.getCodigo())?"RECHAZADO":"REQUIERE_REVISION",e.getCodigo());
        } catch(ServicioExternoNoDisponibleException e) { return error(v,"REQUIERE_REVISION",e.getCodigo());
        } catch(AccesoDenegadoException e) { return error(v,"RECHAZADO",e.getCodigo());
        } catch(IllegalArgumentException e) { return error(v,"RECHAZADO","VENTA_OFFLINE_INVALIDA");
        } catch(RuntimeException e) { return error(v,"REQUIERE_REVISION","CONCILIACION_PENDIENTE"); }
    }
    private Resultado error(VentaOffline v,String estado,String motivo) {
        return new Resultado(v==null?null:v.ventaLocalUuid(),estado,null,null,motivo);
    }
}

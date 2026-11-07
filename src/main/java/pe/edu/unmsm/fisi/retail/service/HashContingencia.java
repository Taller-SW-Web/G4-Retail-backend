package pe.edu.unmsm.fisi.retail.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest.VentaOffline;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
@Component @RequiredArgsConstructor
public class HashContingencia {
    private final ObjectMapper json;
    public String calcular(SincronizacionOfflineRequest lote, VentaOffline v) {
        try {
            var canon=Arrays.asList(lote.terminalPos(),lote.turnoId().toString(),v.ventaLocalUuid().toString(),
                v.correlativoContingencia(),v.fechaHoraOffline(),
                v.items().stream().map(i -> List.of(i.sku(),i.cantidad(),centimos(i.precioUnitario()))).toList(),
                centimos(v.totalNeto()),centimos(v.efectivoRecibido()),centimos(v.vuelto()),v.clienteId());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                json.writeValueAsString(canon).getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) { throw new IllegalArgumentException("No se pudo verificar la venta offline",e); }
    }
    private long centimos(BigDecimal n) { return n.movePointRight(2).longValueExact(); }
}

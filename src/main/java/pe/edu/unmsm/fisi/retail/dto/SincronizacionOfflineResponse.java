package pe.edu.unmsm.fisi.retail.dto;
import java.util.List;
import java.util.UUID;
public record SincronizacionOfflineResponse(int totalProcesados, List<Resultado> resultados) {
    public record Resultado(UUID ventaLocalUuid, String estado, String pedidoId, String comprobante, String motivo) {}
}

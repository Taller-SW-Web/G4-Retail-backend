package pe.edu.unmsm.fisi.retail.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reenvia a Ventas, cada 5 minutos, los pedidos de los cambios que quedaron {@code PENDIENTE} porque
 * Ventas no respondio (F9, contrato 5.9). Usa la misma {@code Idempotency-Key}, asi que un pedido que
 * Ventas si alcanzo a crear no se duplica.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CambioPedidosPendientesJob {

    private final CambioService cambios;

    @Scheduled(fixedDelayString = "${retail.cambios.reintento-pedidos-ms:300000}",
            initialDelayString = "${retail.cambios.reintento-pedidos-ms:300000}")
    public void reintentar() {
        try {
            int confirmados = cambios.reintentarPedidosPendientes();
            if (confirmados > 0) {
                log.info("Pedidos de cambio confirmados por Ventas en el reintento: {}", confirmados);
            }
        } catch (RuntimeException e) {
            log.warn("No se pudieron reintentar los pedidos de cambio pendientes: {}", e.getMessage());
        }
    }
}

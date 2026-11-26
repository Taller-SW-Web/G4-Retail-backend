package pe.edu.unmsm.fisi.retail.service.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job que vacía el outbox cada cierto tiempo (contrato v0.5, §2.9).
 *
 * <p>Se apaga con {@code retail.outbox.habilitado=false} (por ejemplo, en pruebas que controlan el
 * envío a mano). El intervalo se configura con {@code RETAIL_OUTBOX_INTERVALO_MS}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "retail.outbox.habilitado", havingValue = "true", matchIfMissing = true)
public class OutboxReenvioJob {

    private final OutboxDespachador despachador;

    @Scheduled(initialDelayString = "${retail.outbox.retardo-inicial-ms:30000}",
            fixedDelayString = "${retail.outbox.intervalo-ms:30000}")
    public void ejecutar() {
        try {
            despachador.procesarPendientes();
        } catch (RuntimeException e) {
            // Un fallo de base de datos no debe matar el hilo del planificador: se reintenta en la próxima pasada.
            log.error("Outbox: la pasada del job falló y se reintentará: {}", e.getMessage(), e);
        }
    }
}

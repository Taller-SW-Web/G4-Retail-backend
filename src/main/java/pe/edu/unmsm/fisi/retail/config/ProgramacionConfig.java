package pe.edu.unmsm.fisi.retail.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Habilita las tareas programadas (job del outbox, contrato v0.5 §2.9) y expone el reloj del
 * sistema en UTC.
 *
 * <p>El {@link Clock} se inyecta para que las pruebas controlen el tiempo de los reintentos.</p>
 */
@Configuration
@EnableScheduling
public class ProgramacionConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock reloj() {
        return Clock.systemUTC();
    }
}

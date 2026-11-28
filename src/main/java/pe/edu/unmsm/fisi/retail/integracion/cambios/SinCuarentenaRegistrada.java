package pe.edu.unmsm.fisi.retail.integracion.cambios;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puerto de cuarentena por defecto, mientras el servicio de F12 no lo implemente: ningun SKU esta en
 * cuarentena. Se desactiva solo en cuanto exista otro {@link SkusEnCuarentenaPort}.
 */
@Configuration
public class SinCuarentenaRegistrada {

    @Bean
    @ConditionalOnMissingBean(SkusEnCuarentenaPort.class)
    public SkusEnCuarentenaPort skusEnCuarentena() {
        return (tiendaId, sku) -> false;
    }
}

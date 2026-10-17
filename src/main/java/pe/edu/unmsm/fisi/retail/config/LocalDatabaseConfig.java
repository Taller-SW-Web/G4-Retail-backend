package pe.edu.unmsm.fisi.retail.config;

import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * En el perfil local (H2 en memoria), fuerza siempre 'create-drop' para asegurar
 * que las tablas existan en memoria, incluso si una variable de entorno como
 * SPRING_JPA_HIBERNATE_DDL_AUTO configurada para la nube intenta forzar 'validate' o 'none'.
 */
@Profile("local")
@Configuration
public class LocalDatabaseConfig implements HibernatePropertiesCustomizer {

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put("hibernate.hbm2ddl.auto", "create-drop");
    }
}

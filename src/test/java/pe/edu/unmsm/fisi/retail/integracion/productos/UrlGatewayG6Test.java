package pe.edu.unmsm.fisi.retail.integracion.productos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Contrato v0.5.1, sección 4.2: sin variables de entorno, Productos e Inventory (G6) apuntan al
 * gateway compartido de G6 en Azure. Pasar a real queda en quitar el perfil mock y cambiar los modos.
 */
class UrlGatewayG6Test {

    private static final String GATEWAY_G6 =
            "https://api-gateway.calmgrass-aec0b33c.chilecentral.azurecontainerapps.io/api/v1";

    @Test
    void productosEInventarioUsanElGatewayDeG6PorDefecto() throws Exception {
        Properties propiedades = new Properties();
        try (InputStream entrada = getClass().getResourceAsStream("/application.properties")) {
            propiedades.load(entrada);
        }

        assertThat(propiedades.getProperty("retail.integracion.productos.url"))
                .isEqualTo("${RETAIL_PRODUCTOS_URL:" + GATEWAY_G6 + "}");
        assertThat(propiedades.getProperty("retail.integracion.inventario.url"))
                .isEqualTo("${RETAIL_INVENTARIO_URL:" + GATEWAY_G6 + "}");
    }
}

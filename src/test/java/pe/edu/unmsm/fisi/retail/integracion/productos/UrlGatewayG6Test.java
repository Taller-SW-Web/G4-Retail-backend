package pe.edu.unmsm.fisi.retail.integracion.productos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Contrato v0.5.7, sección 4.2: sin variables de entorno, Productos e Inventory (G6) apuntan al
 * Azure API Management de G6: desde el 27/11 el gateway directo responde 403 RBAC a todo consumidor.
 */
class UrlGatewayG6Test {

    private static final String GATEWAY_G6 =
            "https://apim-g6-productos-chile.azure-api.net/api/v1";

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

package pe.edu.unmsm.fisi.retail.integracion.productos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pasar del doble al G6 real es solo configuración (contrato v0.5, §6): sin el perfil {@code mock}
 * y con {@code RETAIL_PRODUCTOS_URL}, la aplicación arranca con {@link ProductosHttp}.
 */
@SpringBootTest(properties = {
        "retail.integracion.productos.url=https://g6.example/api/v1",
        "retail.outbox.habilitado=false"
})
@ActiveProfiles("local")
@DisplayName("ProductosG6Config — G6 real sin el perfil mock")
class ProductosG6ConfigTest {

    @Autowired
    private ProductosPort productos;

    @Test
    @DisplayName("Sin el perfil mock el puerto de Productos es el adaptador HTTP real")
    void usaAdaptadorReal() {
        assertThat(productos).isInstanceOf(ProductosHttp.class);
    }
}

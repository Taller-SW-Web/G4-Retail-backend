package pe.edu.unmsm.fisi.retail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que el contexto de Spring levanta completo: entidades, repositorios,
 * configuracion de CORS y manejo de errores.
 */
@SpringBootTest
@ActiveProfiles({"local", "mock"})
class RetailApplicationTests {

    @Autowired
    private ProductosPort productos;

    @Test
    @DisplayName("El contexto de la aplicacion arranca sin errores")
    void contextoArranca() {
        // Si alguna configuracion o mapeo estuviera mal, este test falla al construir el contexto.
    }

    @Test
    @DisplayName("Con el perfil mock, el doble de Productos queda inyectado")
    void elMockDeProductosQuedaInyectado() {
        assertThat(productos).isNotNull();
        assertThat(productos.resolverCodigoBarras("7750101000012")).isPresent();
    }
}

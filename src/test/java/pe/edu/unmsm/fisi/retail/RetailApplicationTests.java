package pe.edu.unmsm.fisi.retail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifica que el contexto de Spring levanta completo: entidades, repositorios,
 * configuracion de CORS y manejo de errores.
 */
@SpringBootTest
@ActiveProfiles("local")
class RetailApplicationTests {

    @Test
    @DisplayName("El contexto de la aplicacion arranca sin errores")
    void contextoArranca() {
        // Si alguna configuracion o mapeo estuviera mal, este test falla al construir el contexto.
    }
}

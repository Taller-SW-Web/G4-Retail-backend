package pe.edu.unmsm.fisi.retail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada del backend del Modulo Retail (ms-retail).
 *
 * <p>Retail es el canal de mostrador: atiende a la SPA del vendedor y se apoya en los modulos
 * Seguridad (G7), Productos (G6) y Ventas (G5), que en la Semana 8 se simulan con mocks
 * controlados (seccion 6 del contrato de API v0.4).</p>
 */
@SpringBootApplication
public class RetailApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetailApplication.class, args);
    }
}

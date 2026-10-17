package pe.edu.unmsm.fisi.retail.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unmsm.fisi.retail.dto.SaludResponse;

/**
 * Latido del servicio, que la SPA consulta para decidir si entra en modo contingencia (F10).
 *
 * <p>Es el unico endpoint <strong>sin autenticacion</strong> del contrato (seccion 5.1): si exigiera
 * token, un vendedor con la sesion vencida no podria distinguir "se cayo el servidor" de "mi token
 * expiro", que es justo lo que necesita saber para seguir vendiendo sin conexion.</p>
 */
@RestController
@RequestMapping("/api/v1/retail")
public class SaludController {

    @GetMapping("/health")
    public SaludResponse estado() {
        return SaludResponse.arriba();
    }
}

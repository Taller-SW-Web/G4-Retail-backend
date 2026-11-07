package pe.edu.unmsm.fisi.retail.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;

import java.util.UUID;

/**
 * Servicio adaptador para retrocompatibilidad con componentes que deleguen el cobro
 * en mostrador. La lógica transaccional y consolidada reside en {@link VentaService}.
 */
@Service
@RequiredArgsConstructor
public class CobroMostradorService {

    private final VentaService ventaService;

    public VentaResponse registrar(UUID usuario, String clave, VentaRequest solicitud) {
        return ventaService.registrarVenta(solicitud, clave, usuario);
    }
}

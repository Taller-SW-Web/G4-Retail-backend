package pe.edu.unmsm.fisi.retail.controller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import pe.edu.unmsm.fisi.retail.dto.*;
import pe.edu.unmsm.fisi.retail.service.SincronizacionOfflineService;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/retail/contingencia") @RequiredArgsConstructor
public class ContingenciaController {
    private final SincronizacionOfflineService servicio;
    @PostMapping("/sincronizar")
    public SincronizacionOfflineResponse sincronizar(@Valid @RequestBody SincronizacionOfflineRequest lote,
        @AuthenticationPrincipal Jwt usuario) {
        return servicio.sincronizar(lote,UUID.fromString(usuario.getSubject()));
    }
}

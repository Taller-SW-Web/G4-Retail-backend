package pe.edu.unmsm.fisi.retail.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoResponse;
import pe.edu.unmsm.fisi.retail.dto.CarritoEsperaDto;
import pe.edu.unmsm.fisi.retail.service.CarritoService;

import java.util.List;

/**
 * Controlador REST para operaciones de carrito de compras en mostrador POS (F4).
 *
 * <pre>
 * POST /api/v1/retail/carritos/calcular
 * POST /api/v1/retail/carritos-espera
 * GET  /api/v1/retail/carritos-espera
 * </pre>
 *
 * <p><strong>Alcance (RF-10, RF-11, RF-12):</strong></p>
 * <ul>
 *   <li>RF-10: Suspensión y recuperación de carritos en espera para atención en probadores.</li>
 *   <li>RF-11: Aplicación dinámica de cupones y ofertas comerciales.</li>
 *   <li>RF-12: Cálculo fiscal oficial según fórmula SUNAT (Base Imponible, IGV 18%, Total a Pagar).</li>
 * </ul>
 *
 * <p><strong>Límites estrictos:</strong> Solo matemática financiera y gestión de carritos suspendidos.
 * NO emite boletas ni cierra turnos de caja (eso corresponde a F5 y F8).</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/retail")
@RequiredArgsConstructor
public class CarritoController {

    private final CarritoService carritoService;

    /**
     * Calcula la vista previa de totales, descuentos e impuestos del carrito.
     */
    @PostMapping("/carritos/calcular")
    public ResponseEntity<CalculoCarritoResponse> calcularTotales(
            @Valid @RequestBody CalculoCarritoRequest request
    ) {
        CalculoCarritoResponse respuesta = carritoService.calcularTotales(request);
        return ResponseEntity.ok(respuesta);
    }

    /**
     * Pausa una venta en curso guardándola con un alias identificador.
     */
    @PostMapping("/carritos-espera")
    public ResponseEntity<CarritoEsperaDto> guardarCarritoEspera(
            @Valid @RequestBody CarritoEsperaDto request
    ) {
        CarritoEsperaDto guardado = carritoService.guardarCarritoEspera(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(guardado);
    }

    /**
     * Consulta la bandeja de carritos en espera de la tienda.
     */
    @GetMapping("/carritos-espera")
    public ResponseEntity<List<CarritoEsperaDto>> listarCarritosEspera(
            @RequestParam(name = "estado", defaultValue = "SUSPENDIDO") String estado
    ) {
        List<CarritoEsperaDto> lista = carritoService.listarCarritosEspera(estado);
        return ResponseEntity.ok(lista);
    }
}

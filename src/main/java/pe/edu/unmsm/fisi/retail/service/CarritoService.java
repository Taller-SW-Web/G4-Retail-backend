package pe.edu.unmsm.fisi.retail.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoResponse;
import pe.edu.unmsm.fisi.retail.dto.CarritoEsperaDto;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de negocio para la gestión de carrito POS, totales e impuestos (F4).
 *
 * <p><strong>Responsabilidades (RF-10, RF-11, RF-12):</strong></p>
 * <ul>
 *   <li>Calcular subtotales, descuentos y desglose SUNAT (IGV 18%) con redondeo bancario a 2 decimales.</li>
 *   <li>Evaluar cupones de descuento (ej. VERANO2026: 10%) sin bloquear ante cupones inválidos.</li>
 *   <li>Persistencia y consulta de carritos en espera para la atención ágil en probadores.</li>
 * </ul>
 */
@Slf4j
@Service
public class CarritoService {

    private static final BigDecimal FACTOR_BASE_IMPONIBLE = new BigDecimal("1.18");
    private static final BigDecimal DESCUENTO_DIEZ_PORCIENTO = new BigDecimal("0.10");

    /** Almacenamiento concurrente de carritos en espera. */
    private final Map<UUID, CarritoEsperaDto> carritosEnEspera = new ConcurrentHashMap<>();

    /**
     * Calcula los totales del carrito con precisión de centavos según la fórmula oficial de SUNAT.
     */
    public CalculoCarritoResponse calcularTotales(CalculoCarritoRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ReglaNegocioException("CARRITO_VACIO", "El carrito debe contener al menos un item para calcular totales");
        }

        List<String> advertencias = new ArrayList<>();
        boolean aplicaDescuento10 = false;
        String nombrePromo = null;

        if (request.cupon() != null && !request.cupon().isBlank()) {
            String cuponLimpio = request.cupon().trim().toUpperCase();
            if ("VERANO2026".equals(cuponLimpio) || "DESCUENTO10".equals(cuponLimpio)) {
                aplicaDescuento10 = true;
                nombrePromo = "10% Cupón " + cuponLimpio;
            } else {
                advertencias.add("CUPON_INVALIDO");
            }
        }

        List<CalculoCarritoResponse.LineaCalculoDto> lineas = new ArrayList<>();
        BigDecimal subtotalBruto = BigDecimal.ZERO;
        BigDecimal descuentoTotal = BigDecimal.ZERO;

        for (var item : request.items()) {
            if (item.cantidad() == null || item.cantidad() <= 0) {
                throw new ReglaNegocioException("CANTIDAD_INVALIDA", "La cantidad para el SKU " + item.sku() + " debe ser mayor a 0");
            }

            BigDecimal precioUnitario = item.precioUnitario() != null
                    ? item.precioUnitario()
                    : new BigDecimal("100.00"); // Precio de referencia por defecto si no es provisto

            BigDecimal lineaSubtotal = precioUnitario.multiply(BigDecimal.valueOf(item.cantidad()))
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal descuentoLinea = BigDecimal.ZERO;
            String promoAplicada = null;

            if (aplicaDescuento10) {
                descuentoLinea = lineaSubtotal.multiply(DESCUENTO_DIEZ_PORCIENTO).setScale(2, RoundingMode.HALF_UP);
                promoAplicada = nombrePromo;
            }

            BigDecimal precioFinal = lineaSubtotal.subtract(descuentoLinea).setScale(2, RoundingMode.HALF_UP);

            subtotalBruto = subtotalBruto.add(lineaSubtotal);
            descuentoTotal = descuentoTotal.add(descuentoLinea);

            lineas.add(new CalculoCarritoResponse.LineaCalculoDto(
                    item.sku(),
                    item.cantidad(),
                    precioUnitario.setScale(2, RoundingMode.HALF_UP),
                    descuentoLinea,
                    precioFinal,
                    promoAplicada
            ));
        }

        BigDecimal totalPagar = subtotalBruto.subtract(descuentoTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal baseImponible = totalPagar.divide(FACTOR_BASE_IMPONIBLE, 2, RoundingMode.HALF_UP);
        BigDecimal montoIgv = totalPagar.subtract(baseImponible).setScale(2, RoundingMode.HALF_UP);

        String evaluacionId = "EVAL-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("Cálculo de carrito procesado: items={}, subtotal={}, total={}",
                lineas.size(), subtotalBruto, totalPagar);

        return new CalculoCarritoResponse(
                lineas,
                subtotalBruto,
                descuentoTotal,
                totalPagar,
                baseImponible,
                montoIgv,
                "PEN",
                evaluacionId,
                advertencias
        );
    }

    /**
     * Registra un ticket de venta en espera con un alias para su posterior reanudación (RF-10).
     */
    public CarritoEsperaDto guardarCarritoEspera(CarritoEsperaDto request) {
        if (request == null || request.aliasTicket() == null || request.aliasTicket().isBlank()) {
            throw new ReglaNegocioException("ALIAS_REQUERIDO", "El identificador o alias de la venta es obligatorio");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new ReglaNegocioException("CARRITO_VACIO", "No se puede suspender una venta sin items");
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime ahora = OffsetDateTime.now();
        OffsetDateTime expiracion = ahora.plusHours(2);

        BigDecimal subtotal = request.items().stream()
                .map(i -> i.precioUnitario().multiply(BigDecimal.valueOf(i.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        CarritoEsperaDto guardado = new CarritoEsperaDto(
                id,
                request.aliasTicket().trim(),
                request.clienteId(),
                request.items(),
                subtotal,
                ahora,
                expiracion,
                "SUSPENDIDO"
        );

        carritosEnEspera.put(id, guardado);
        log.info("Venta suspendida guardada: id={}, alias='{}', subtotal={}", id, guardado.aliasTicket(), subtotal);
        return guardado;
    }

    /**
     * Lista los carritos en espera filtrados por estado (por defecto 'SUSPENDIDO').
     */
    public List<CarritoEsperaDto> listarCarritosEspera(String estado) {
        String estadoFiltro = (estado == null || estado.isBlank()) ? "SUSPENDIDO" : estado.trim().toUpperCase();
        return carritosEnEspera.values().stream()
                .filter(c -> estadoFiltro.equals(c.estado()))
                .toList();
    }
}

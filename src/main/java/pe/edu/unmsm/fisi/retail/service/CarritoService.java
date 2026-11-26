package pe.edu.unmsm.fisi.retail.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoResponse;
import pe.edu.unmsm.fisi.retail.dto.CarritoEsperaDto;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.PromocionesPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.Precio;
import pe.edu.unmsm.fisi.retail.integracion.productos.mock.PromocionesEnMemoria;

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
 *   <li>Evaluar promociones y cupones con el motor de Productos (G6) detrás de {@link PromocionesPort}
 *   (RF-11), repartir el descuento entre las líneas y no bloquear la venta ante un cupón inválido ni
 *   si el motor no responde.</li>
 *   <li>Persistencia y consulta de carritos en espera para la atención ágil en probadores.</li>
 * </ul>
 */
@Slf4j
@Service
public class CarritoService {

    private static final BigDecimal FACTOR_BASE_IMPONIBLE = new BigDecimal("1.18");

    /** Almacenamiento concurrente de carritos en espera. */
    private final Map<UUID, CarritoEsperaDto> carritosEnEspera = new ConcurrentHashMap<>();

    private final PromocionesPort promociones;
    /** Precio vigente por SKU. {@code null} solo en las pruebas que fijan el precio en el item. */
    private final ProductosPort productos;

    /**
     * Contrato §5.4: el precio lo obtiene el backend de Productos (G6) y nunca se confía en el que envía
     * el cliente. Es el mismo precio que usa la venta (F5), así que la vista previa y el cobro coinciden.
     */
    @Autowired
    public CarritoService(PromocionesPort promociones, ProductosPort productos) {
        this.promociones = promociones;
        this.productos = productos;
    }

    /** Para pruebas unitarias del cálculo: toma el precio del item. */
    public CarritoService(PromocionesPort promociones) {
        this.promociones = promociones;
        this.productos = null;
    }

    /** Para pruebas unitarias: usa el doble de promociones con los cupones de prueba. */
    public CarritoService() {
        this(new PromocionesEnMemoria());
    }

    /**
     * Calcula los totales del carrito con precisión de centavos según la fórmula oficial de SUNAT.
     */
    public CalculoCarritoResponse calcularTotales(CalculoCarritoRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ReglaNegocioException("CARRITO_VACIO", "El carrito debe contener al menos un item para calcular totales");
        }

        for (var item : request.items()) {
            if (item.cantidad() == null || item.cantidad() <= 0) {
                throw new ReglaNegocioException("CANTIDAD_INVALIDA", "La cantidad para el SKU " + item.sku() + " debe ser mayor a 0");
            }
        }

        List<String> advertencias = new ArrayList<>();
        String cupon = request.cupon() == null || request.cupon().isBlank() ? null : request.cupon().trim().toUpperCase();

        List<PromocionesPort.Linea> cesta = new ArrayList<>();
        List<BigDecimal> subtotales = new ArrayList<>();
        List<BigDecimal> precios = new ArrayList<>();
        BigDecimal subtotalBruto = BigDecimal.ZERO;
        for (var item : request.items()) {
            BigDecimal precio = precioDe(item);
            precios.add(precio);
            BigDecimal lineaSubtotal = precio.multiply(BigDecimal.valueOf(item.cantidad())).setScale(2, RoundingMode.HALF_UP);
            cesta.add(new PromocionesPort.Linea(item.sku(), item.cantidad(), precio));
            subtotales.add(lineaSubtotal);
            subtotalBruto = subtotalBruto.add(lineaSubtotal);
        }

        PromocionesPort.Evaluacion evaluacion = evaluar(cesta, cupon, advertencias);
        // Nunca se descuenta más que el subtotal, aunque el motor calcule con otros precios.
        BigDecimal descuentoTotal = evaluacion.aplica() ? evaluacion.descuentoTotal().min(subtotalBruto)
                .setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        List<CalculoCarritoResponse.LineaCalculoDto> lineas = new ArrayList<>();
        BigDecimal repartido = BigDecimal.ZERO;
        for (int i = 0; i < request.items().size(); i++) {
            var item = request.items().get(i);
            BigDecimal lineaSubtotal = subtotales.get(i);
            BigDecimal descuentoLinea = BigDecimal.ZERO;
            if (descuentoTotal.signum() > 0 && subtotalBruto.signum() > 0) {
                // Reparto proporcional al subtotal de cada línea; la última absorbe el redondeo.
                descuentoLinea = i == request.items().size() - 1
                        ? descuentoTotal.subtract(repartido)
                        : descuentoTotal.multiply(lineaSubtotal).divide(subtotalBruto, 2, RoundingMode.HALF_UP);
                repartido = repartido.add(descuentoLinea);
            }
            lineas.add(new CalculoCarritoResponse.LineaCalculoDto(
                    item.sku(),
                    item.cantidad(),
                    precios.get(i).setScale(2, RoundingMode.HALF_UP),
                    descuentoLinea,
                    lineaSubtotal.subtract(descuentoLinea).setScale(2, RoundingMode.HALF_UP),
                    descuentoLinea.signum() > 0 ? evaluacion.nombre() : null
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

    /** Consulta el motor de promociones; si no responde, la venta sigue sin descuento (RF-11). */
    private PromocionesPort.Evaluacion evaluar(List<PromocionesPort.Linea> cesta, String cupon, List<String> advertencias) {
        try {
            PromocionesPort.Evaluacion evaluacion = promociones.evaluar(cesta, cupon);
            if (evaluacion.cuponRechazado()) {
                advertencias.add("CUPON_INVALIDO");
            }
            return evaluacion;
        } catch (ServicioExternoNoDisponibleException e) {
            log.warn("Promociones no disponibles ({}): el carrito se calcula sin descuento", e.getMessage());
            advertencias.add("PROMOCIONES_NO_DISPONIBLES");
            return PromocionesPort.Evaluacion.sinBeneficio(false);
        }
    }

    /**
     * Precio vigente del SKU en Productos (G6), con la oferta si la hay. Un SKU sin precio en el canal
     * responde {@code 404 SKU_NO_ENCONTRADO}; si Productos no responde, {@code 503} (contrato §5.4).
     */
    private BigDecimal precioDe(CalculoCarritoRequest.ItemCarritoCalculoDto item) {
        if (productos == null) {
            if (item.precioUnitario() == null) {
                throw new ReglaNegocioException("PRECIO_REQUERIDO", "Falta el precio del SKU " + item.sku());
            }
            return item.precioUnitario();
        }
        return productos.consultarPrecio(item.sku())
                .map(Precio::precioEfectivo)
                .orElseThrow(() -> new RecursoNoEncontradoException("SKU_NO_ENCONTRADO",
                        "El SKU " + item.sku() + " no tiene precio vigente en el catalogo"));
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

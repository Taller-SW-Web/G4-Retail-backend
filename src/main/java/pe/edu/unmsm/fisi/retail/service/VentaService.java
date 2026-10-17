package pe.edu.unmsm.fisi.retail.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logica de negocio del cierre transaccional de ventas en mostrador (F5).
 *
 * <p><strong>Responsabilidades de este servicio (RF-13, RF-14, RF-15):</strong></p>
 * <ul>
 *   <li>Calcular subtotal, IGV 18 % y total a partir de los items de la venta.</li>
 *   <li>Validar que el monto pagado sea coherente con los items declarados.</li>
 *   <li>Generar el correlativo incremental de la boleta B001 (o factura F001).</li>
 *   <li>Garantizar idempotencia: la misma {@code Idempotency-Key} siempre devuelve
 *       la misma {@link VentaResponse}.</li>
 *   <li>Generar el ticket de cambio para regalo si se solicita.</li>
 * </ul>
 *
 * <p><strong>Fuera del alcance de este servicio:</strong></p>
 * <ul>
 *   <li>Validacion de documentos de identidad (DNI/RUC) — F3 ClienteService.</li>
 *   <li>Apertura o cierre de turnos de caja — F8 CajaService.</li>
 *   <li>Descuento de stock de inventario — F2 CatalogoService.</li>
 * </ul>
 *
 * <p>TODO: Cuando Angie (F8) tenga {@code CajaService} listo, agregar validacion de turno
 * abierto antes de ejecutar la venta. Coordinar el {@code turnoId} como parte del request.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VentaService {

    /** IGV vigente en Peru: 18 %. */
    private static final BigDecimal TASA_IGV = new BigDecimal("0.18");

    /** Tolerancia de diferencia entre el monto declarado y el calculado: S/ 0.02 por redondeo. */
    private static final BigDecimal TOLERANCIA_REDONDEO = new BigDecimal("0.02");

    /** Monto a partir del cual una boleta requiere DNI del cliente (SUNAT). */
    private static final BigDecimal MONTO_REQUIERE_DNI = new BigDecimal("700.00");

    /** Contador del correlativo de boleta (persistencia en memoria para el parcial;
     *  TODO: migrar a secuencia en Supabase antes del final). */
    private final AtomicInteger correlativoBoleta = new AtomicInteger(1);
    private final AtomicInteger correlativoFactura = new AtomicInteger(1);

    /** Secuencia global para garantizar unicidad del pedidoId incluso en el mismo milisegundo. */
    private final AtomicInteger secuenciaPedido = new AtomicInteger(1);

    /** Cache de idempotencia en memoria (llave → respuesta ya generada). */
    private final Map<String, VentaResponse> cacheIdempotencia = new ConcurrentHashMap<>();

    // ── API publica ──────────────────────────────────────────────────────────

    /**
     * Registra la venta y emite el comprobante correspondiente.
     *
     * @param request      datos completos de la venta validados por Bean Validation
     * @param idempotencyKey clave unica del cliente para garantizar exactamente un registro
     *                       aunque la red repita la peticion
     * @return respuesta con el {@code pedidoId}, el comprobante emitido y el ticket de regalo
     * @throws ReglaNegocioException si hay incoherencia de montos o violacion de normativa SUNAT
     */
    public VentaResponse registrarVenta(VentaRequest request, String idempotencyKey) {

        // Idempotencia: si ya procesamos esta llave, devolver la misma respuesta
        if (cacheIdempotencia.containsKey(idempotencyKey)) {
            log.info("Idempotencia activada para llave={}", idempotencyKey);
            return cacheIdempotencia.get(idempotencyKey);
        }

        // 1. Calcular totales fiscales
        BigDecimal totalCalculado = calcularTotal(request);
        validarMontoPago(request, totalCalculado);
        validarNormativaSunat(request, totalCalculado);

        // 2. Generar correlativo y pedidoId
        boolean esFactura = "FACTURA".equals(request.comprobante().tipo());
        String serie = esFactura ? "F001" : "B001";
        int numCorrelativo = esFactura
                ? correlativoFactura.getAndIncrement()
                : correlativoBoleta.getAndIncrement();
        String correlativo = String.format("%05d", numCorrelativo);
        String pedidoId = generarPedidoId();

        // 3. Calcular descomposicion fiscal
        BigDecimal igv = totalCalculado
                .multiply(TASA_IGV)
                .divide(BigDecimal.ONE.add(TASA_IGV), 2, RoundingMode.HALF_UP);
        BigDecimal subtotal = totalCalculado.subtract(igv).setScale(2, RoundingMode.HALF_UP);

        // 4. Armar comprobante
        OffsetDateTime ahora = OffsetDateTime.now();
        VentaResponse.ComprobanteEmitido comprobante = new VentaResponse.ComprobanteEmitido(
                serie,
                correlativo,
                ahora,
                subtotal,
                igv,
                totalCalculado
        );

        // 5. Ticket de regalo (opcional)
        VentaResponse.TicketRegalo ticketRegalo = null;
        if (request.emitirTicketRegalo()) {
            ticketRegalo = new VentaResponse.TicketRegalo(
                    "CANJE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    ahora.plusDays(30)
            );
        }

        VentaResponse respuesta = new VentaResponse(pedidoId, comprobante, ticketRegalo);

        // 6. Guardar en cache de idempotencia y loguear auditoria
        cacheIdempotencia.put(idempotencyKey, respuesta);
        log.info("Venta registrada: pedidoId={} serie={} correlativo={} canal={} vendedor={} total={}",
                pedidoId, serie, correlativo, request.canal(), request.vendedorId(), totalCalculado);

        return respuesta;
    }

    // ── Metodos privados de calculo ─────────────────────────────────────────

    /**
     * Suma {@code cantidad × precioFinal} de cada item y redondea a 2 decimales.
     *
     * <p>El total incluye IGV (precio de lista con impuesto incluido, segun convencion
     * peruana de precios al publico).</p>
     */
    BigDecimal calcularTotal(VentaRequest request) {
        return request.items().stream()
                .map(item -> item.precioFinal().multiply(BigDecimal.valueOf(item.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Verifica que el monto declarado en el pago no difiera del total calculado por mas
     * de la tolerancia de redondeo ({@value #TOLERANCIA_REDONDEO} S/).
     *
     * @throws ReglaNegocioException si la diferencia supera la tolerancia
     */
    void validarMontoPago(VentaRequest request, BigDecimal totalCalculado) {
        BigDecimal diferencia = request.pago().monto().subtract(totalCalculado).abs();
        if (diferencia.compareTo(TOLERANCIA_REDONDEO) > 0) {
            throw new ReglaNegocioException(
                    "MONTO_INCONSISTENTE",
                    String.format("El monto del pago declarado (S/ %s) no coincide con el total calculado (S/ %s)",
                            request.pago().monto(), totalCalculado)
            );
        }
    }

    /**
     * Aplica las reglas tributarias SUNAT para la emision del comprobante:
     * <ul>
     *   <li>FACTURA requiere que {@code numeroDocumento} sea un RUC de 11 digitos.</li>
     *   <li>BOLETA con monto &ge; S/ 700 requiere que {@code numeroDocumento} sea un DNI.</li>
     * </ul>
     *
     * @throws ReglaNegocioException si se viola alguna de las reglas
     */
    void validarNormativaSunat(VentaRequest request, BigDecimal totalCalculado) {
        String tipo = request.comprobante().tipo();
        String doc = request.comprobante().numeroDocumento();

        if ("FACTURA".equals(tipo)) {
            if (doc == null || doc.length() != 11) {
                throw new ReglaNegocioException(
                        "FACTURA_REQUIERE_RUC",
                        "Para emitir Factura debe proporcionar un RUC valido de 11 digitos"
                );
            }
        }

        if ("BOLETA".equals(tipo) && totalCalculado.compareTo(MONTO_REQUIERE_DNI) >= 0) {
            if (doc == null || doc.length() != 8) {
                throw new ReglaNegocioException(
                        "BOLETA_REQUIERE_DNI",
                        "Por normativa SUNAT, ventas mayores o iguales a S/ 700.00 requieren el DNI del cliente"
                );
            }
        }
    }

    /** Genera un ID de pedido unico con marca de tiempo y secuencia para trazabilidad. */
    private String generarPedidoId() {
        OffsetDateTime ahora = OffsetDateTime.now();
        return String.format("VNT-%d%02d%02d-%05d",
                ahora.getYear(), ahora.getMonthValue(), ahora.getDayOfMonth(),
                secuenciaPedido.getAndIncrement());
    }
}

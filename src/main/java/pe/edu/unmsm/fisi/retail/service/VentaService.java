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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logica de negocio del cierre transaccional de ventas en mostrador (F5).
 *
 * <p><strong>Responsabilidades de este servicio (RF-13, RF-14, RF-15):</strong></p>
 * <ul>
 *   <li>Validar que el turno de caja este abierto antes de procesar la venta.</li>
 *   <li>Calcular subtotal, IGV 18 % y total a partir de los items de la venta.</li>
 *   <li>Validar el pago: para efectivo, que el monto recibido cubra el total (y calcular vuelto);
 *       para tarjeta, que la referencia de operacion sea valida.</li>
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VentaService {

    /** IGV vigente en Peru: 18 %. */
    private static final BigDecimal TASA_IGV = new BigDecimal("0.18");

    /** Monto a partir del cual una boleta requiere DNI del cliente (SUNAT). */
    private static final BigDecimal MONTO_REQUIERE_DNI = new BigDecimal("700.00");

    /** RUC mock de la empresa para el QR de verificacion SUNAT (Semana 8). */
    private static final String RUC_EMPRESA_MOCK = "20123456789";

    /** Contador del correlativo de boleta (persistencia en memoria para el parcial;
     *  TODO: migrar a secuencia en Supabase antes del final). */
    private final AtomicInteger correlativoBoleta = new AtomicInteger(1);
    private final AtomicInteger correlativoFactura = new AtomicInteger(1);

    /** Secuencia global para garantizar unicidad del pedidoId incluso en el mismo milisegundo. */
    private final AtomicInteger secuenciaPedido = new AtomicInteger(1);

    /** Cache de idempotencia en memoria (llave → respuesta ya generada). */
    private final Map<String, VentaResponse> cacheIdempotencia = new ConcurrentHashMap<>();

    /**
     * Mock Semana 8: conjunto de turnoIds abiertos en memoria.
     * Cuando Angie (F8) tenga {@code CajaService} listo, reemplazar por
     * consulta a la tabla {@code RET_CAJA_SESION}.
     */
    private final Set<String> turnosAbiertos = ConcurrentHashMap.newKeySet();

    // ── API publica ──────────────────────────────────────────────────────────

    /**
     * Registra un turno como abierto (mock Semana 8).
     *
     * <p>Este metodo existe solo hasta que CajaService implemente la apertura real.
     * La validacion de turno en {@link #registrarVenta} consulta este set.</p>
     *
     * @param turnoId identificador del turno que se marca como abierto
     */
    public void abrirTurnoMock(String turnoId) {
        turnosAbiertos.add(turnoId);
    }

    /**
     * Registra la venta y emite el comprobante correspondiente.
     *
     * @param request      datos completos de la venta validados por Bean Validation
     * @param idempotencyKey clave unica del cliente para garantizar exactamente un registro
     *                       aunque la red repita la peticion
     * @return respuesta con el {@code pedidoId}, el comprobante emitido y el ticket de regalo
     * @throws ReglaNegocioException si hay incoherencia de montos, turno cerrado o violacion SUNAT
     */
    public VentaResponse registrarVenta(VentaRequest request, String idempotencyKey) {

        // Idempotencia: si ya procesamos esta llave, devolver la misma respuesta
        if (cacheIdempotencia.containsKey(idempotencyKey)) {
            log.info("Idempotencia activada para llave={}", idempotencyKey);
            return cacheIdempotencia.get(idempotencyKey);
        }

        // 0. Validar turno de caja abierto (precondicion del contrato §5.5)
        validarTurnoAbierto(request.turnoId());

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
        String ventaId = UUID.randomUUID().toString();

        // 3. Calcular descomposicion fiscal
        BigDecimal igv = totalCalculado
                .multiply(TASA_IGV)
                .divide(BigDecimal.ONE.add(TASA_IGV), 2, RoundingMode.HALF_UP);
        BigDecimal subtotal = totalCalculado.subtract(igv).setScale(2, RoundingMode.HALF_UP);

        // 4. Calcular vuelto segun medio de pago
        String medioPago = request.pago().medioPago();
        BigDecimal montoRecibido;
        BigDecimal vuelto;
        if ("EFECTIVO".equals(medioPago)) {
            montoRecibido = request.pago().montoRecibidoEfectivo();
            vuelto = montoRecibido.subtract(totalCalculado).setScale(2, RoundingMode.HALF_UP);
        } else {
            montoRecibido = totalCalculado;
            vuelto = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        // 5. Armar cadena QR de verificacion SUNAT
        String doc = request.comprobante().numeroDocumento() != null
                ? request.comprobante().numeroDocumento() : "";
        String qr = String.join("|",
                RUC_EMPRESA_MOCK, serie, correlativo,
                igv.toPlainString(), totalCalculado.toPlainString(), doc);

        // 6. Armar comprobante
        OffsetDateTime ahora = OffsetDateTime.now();
        VentaResponse.ComprobanteEmitido comprobante = new VentaResponse.ComprobanteEmitido(
                request.comprobante().tipo(),
                serie,
                correlativo,
                subtotal,
                igv,
                totalCalculado,
                ahora,
                qr
        );

        // 7. Armar resumen de pago
        VentaResponse.PagoResumen pagoResumen = new VentaResponse.PagoResumen(
                medioPago, montoRecibido, vuelto
        );

        // 8. Ticket de regalo (opcional)
        VentaResponse.TicketRegalo ticketRegalo = null;
        if (request.emitirTicketRegalo()) {
            ticketRegalo = new VentaResponse.TicketRegalo(
                    "CANJE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    ahora.plusDays(30)
            );
        }

        VentaResponse respuesta = new VentaResponse(
                ventaId, pedidoId, "PAGADO", pagoResumen, comprobante, ticketRegalo
        );

        // 9. Guardar en cache de idempotencia y loguear auditoria
        cacheIdempotencia.put(idempotencyKey, respuesta);
        log.info("Venta registrada: ventaId={} pedidoId={} serie={} correlativo={} terminal={} total={} vuelto={}",
                ventaId, pedidoId, serie, correlativo, request.terminalPos(), totalCalculado, vuelto);

        return respuesta;
    }

    // ── Metodos privados de calculo ─────────────────────────────────────────

    /**
     * Valida que el turno de caja este abierto antes de procesar la venta.
     *
     * <p>Mock Semana 8: verifica contra un {@link Set} en memoria. Cuando Angie (F8)
     * implemente {@code CajaService}, reemplazar por consulta a {@code RET_CAJA_SESION}
     * con estado {@code ABIERTA}.</p>
     *
     * @throws ReglaNegocioException con codigo {@code TURNO_NO_ABIERTO}
     *         (el contrato indica 409, pero se usa 422 temporalmente hasta que exista
     *          una excepcion especifica para conflictos de estado)
     */
    void validarTurnoAbierto(String turnoId) {
        if (!turnosAbiertos.contains(turnoId)) {
            throw new ReglaNegocioException(
                    "TURNO_NO_ABIERTO",
                    "No hay un turno de caja abierto con id " + turnoId +
                    ". Debe abrir un turno antes de registrar ventas."
            );
        }
    }

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
     * Valida el pago segun el medio utilizado (contrato §5.5, RF-13):
     * <ul>
     *   <li>EFECTIVO: {@code montoRecibidoEfectivo >= totalPagar}. Si no cubre, lanza
     *       {@code MONTO_INSUFICIENTE} indicando el faltante.</li>
     *   <li>TARJETA_POS: {@code referenciaOperacion} obligatoria, minimo 4 caracteres.</li>
     * </ul>
     *
     * @throws ReglaNegocioException si el pago no cumple las reglas del medio seleccionado
     */
    void validarMontoPago(VentaRequest request, BigDecimal totalCalculado) {
        String medioPago = request.pago().medioPago();

        if ("EFECTIVO".equals(medioPago)) {
            BigDecimal recibido = request.pago().montoRecibidoEfectivo();
            if (recibido == null || recibido.compareTo(totalCalculado) < 0) {
                BigDecimal faltante = recibido == null
                        ? totalCalculado
                        : totalCalculado.subtract(recibido).setScale(2, RoundingMode.HALF_UP);
                throw new ReglaNegocioException(
                        "MONTO_INSUFICIENTE",
                        String.format(
                                "El monto recibido en efectivo (S/ %s) es menor al total a pagar (S/ %s). Faltan S/ %s",
                                recibido != null ? recibido : "0.00", totalCalculado, faltante)
                );
            }
        } else if ("TARJETA_POS".equals(medioPago)) {
            String ref = request.pago().referenciaOperacion();
            if (ref == null || ref.trim().length() < 4) {
                throw new ReglaNegocioException(
                        "REFERENCIA_VOUCHER_INVALIDA",
                        "Para pago con tarjeta, la referencia de operacion es obligatoria y debe tener al menos 4 caracteres"
                );
            }
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

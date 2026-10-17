package pe.edu.unmsm.fisi.retail.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import pe.edu.unmsm.fisi.retail.client.VentasClient;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;
import pe.edu.unmsm.fisi.retail.client.dto.VentasPedidoResponse;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *   <li>Integrar con el Módulo de Ventas (G5) para persistencia centralizada con fallback local.</li>
 * </ul>
 */
@Slf4j
@Service
public class VentaService {

    /** IGV vigente en Peru: 18 %. */
    private static final BigDecimal TASA_IGV = new BigDecimal("0.18");

    /** Monto a partir del cual una boleta requiere DNI del cliente (SUNAT). */
    private static final BigDecimal MONTO_REQUIERE_DNI = new BigDecimal("700.00");

    /** RUC mock de la empresa para el QR de verificacion SUNAT (Semana 8). */
    private static final String RUC_EMPRESA_MOCK = "20123456789";

    /** Cliente REST para comunicación con Ventas y Postventa (G5). */
    private final VentasClient ventasClient;

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

    /** Constructor sin argumentos para compatibilidad con tests unitarios existentes. */
    public VentaService() {
        this.ventasClient = null;
    }

    /** Constructor principal con inyección opcional del cliente de Ventas. */
    @Autowired
    public VentaService(@Autowired(required = false) VentasClient ventasClient) {
        this.ventasClient = ventasClient;
    }

    // ── API publica ──────────────────────────────────────────────────────────

    /**
     * Registra un turno de caja como abierto en la simulacion en memoria.
     * Solo para uso en tests y desarrollo local hasta que F8 este conectado.
     */
    public void abrirTurnoMock(String turnoId) {
        turnosAbiertos.add(turnoId);
    }

    /**
     * Cierra un turno de caja en la simulacion en memoria.
     */
    public void cerrarTurnoMock(String turnoId) {
        turnosAbiertos.remove(turnoId);
    }

    /**
     * Procesa y registra una venta en mostrador (F5).
     *
     * <p>Flujo de ejecucion:</p>
     * <ol>
     *   <li>Verifica si la {@code Idempotency-Key} ya fue procesada; si es asi,
     *       devuelve la respuesta almacenada inmediatamente.</li>
     *   <li>Valida que el turno de caja este abierto (RF-13).</li>
     *   <li>Calcula el total a pagar y valida el medio de pago (RF-13).</li>
     *   <li>Valida la normativa SUNAT sobre el comprobante (RF-14).</li>
     *   <li>Calcula la descomposicion fiscal: subtotal e IGV 18 % (RF-12).</li>
     *   <li>Calcula el vuelto segun el monto recibido (RF-13).</li>
     *   <li>Genera la serie y correlativo del comprobante (RF-14).</li>
     *   <li>Construye la cadena QR para impresion termica (RF-14).</li>
     *   <li>Si se solicito ticket de regalo, genera su codigo de canje (RF-15).</li>
     *   <li>Almacena la respuesta en la cache de idempotencia y registra log de auditoria.</li>
     * </ol>
     *
     * @param request        datos de la venta (items, pago, comprobante, etc.)
     * @param idempotencyKey identificador unico de la operacion enviado por el frontend
     * @return respuesta con los datos de la venta registrada y el comprobante emitido
     * @throws ReglaNegocioException si alguna validacion de negocio falla
     */
    public VentaResponse registrarVenta(VentaRequest request, String idempotencyKey) {
        // 1. Validar idempotencia: si la llave ya fue procesada, devolver la misma respuesta
        if (idempotencyKey != null && cacheIdempotencia.containsKey(idempotencyKey)) {
            log.info("Idempotencia activada para llave={}", idempotencyKey);
            return cacheIdempotencia.get(idempotencyKey);
        }

        // 2. Validar reglas de negocio
        validarTurnoAbierto(request.turnoId());
        BigDecimal totalCalculado = calcularTotal(request);
        validarMontoPago(request, totalCalculado);
        validarNormativaSunat(request, totalCalculado);

        OffsetDateTime ahora = OffsetDateTime.now();
        String pedidoId = resolverPedidoId(request, totalCalculado, ahora, idempotencyKey);
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

        // 5. Determinar serie y correlativo segun tipo de comprobante
        String tipoComprobante = request.comprobante().tipo();
        String serie = "FACTURA".equals(tipoComprobante) ? "F001" : "B001";
        int correlativoNum = "FACTURA".equals(tipoComprobante)
                ? correlativoFactura.getAndIncrement()
                : correlativoBoleta.getAndIncrement();
        String correlativo = String.format("%05d", correlativoNum);

        // 6. Armar cadena QR de verificacion SUNAT
        String doc = request.comprobante().numeroDocumento() != null
                ? request.comprobante().numeroDocumento() : "";
        String qr = String.join("|",
                RUC_EMPRESA_MOCK, serie, correlativo,
                igv.toPlainString(), totalCalculado.toPlainString(), doc);

        // 7. Armar comprobante
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

        // 8. Armar resumen de pago
        VentaResponse.PagoResumen pagoResumen = new VentaResponse.PagoResumen(
                medioPago, montoRecibido, vuelto
        );

        // 9. Ticket de regalo (opcional)
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

        // 10. Guardar en cache de idempotencia y loguear auditoria
        cacheIdempotencia.put(idempotencyKey, respuesta);
        log.info("Venta registrada: ventaId={} pedidoId={} serie={} correlativo={} terminal={} total={} vuelto={}",
                ventaId, pedidoId, serie, correlativo, request.terminalPos(), totalCalculado, vuelto);

        return respuesta;
    }

    // ── Metodos privados de calculo ─────────────────────────────────────────

    /**
     * Valida que el turno de caja este abierto antes de procesar la venta.
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
     */
    BigDecimal calcularTotal(VentaRequest request) {
        return request.items().stream()
                .map(item -> item.precioFinal().multiply(BigDecimal.valueOf(item.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Valida el pago segun el medio utilizado (contrato §5.5, RF-13).
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
                        "Para pago con tarjeta POS debe ingresar una referencia de voucher valida (minimo 4 caracteres)"
                );
            }
        }
    }

    /**
     * Valida las reglas tributarias peruanas sobre comprobantes de pago.
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

    /**
     * Resuelve el identificador del pedido delegando en el microservicio de Ventas (G5)
     * o aplicando fallback a generación local en caso de error o servicio inactivo.
     */
    private String resolverPedidoId(VentaRequest request, BigDecimal totalCalculado,
                                    OffsetDateTime ahora, String idempotencyKey) {
        if (ventasClient != null) {
            try {
                VentasCrearPedidoRequest pedidoRequest = armarPedidoVentasRequest(request, totalCalculado, ahora);
                Optional<VentasPedidoResponse> respuestaVentas = ventasClient.crearPedido(pedidoRequest, idempotencyKey);
                if (respuestaVentas.isPresent() && respuestaVentas.get().pedidoId() != null) {
                    log.info("Pedido registrado exitosamente con identificador de Ventas G5: {}",
                            respuestaVentas.get().pedidoId());
                    return respuestaVentas.get().pedidoId();
                }
            } catch (Exception e) {
                log.warn("Excepción al comunicarse con Ventas G5 (activando fallback local): {}", e.getMessage());
            }
        }
        return generarPedidoId();
    }

    /**
     * Transforma los datos de la venta de mostrador al formato canónico de Ventas (G5).
     */
    VentasCrearPedidoRequest armarPedidoVentasRequest(VentaRequest request, BigDecimal totalCalculado, OffsetDateTime ahora) {
        UUID clienteUuid;
        try {
            clienteUuid = (request.clienteId() != null && !request.clienteId().isBlank())
                    ? UUID.fromString(request.clienteId())
                    : UUID.fromString("00000000-0000-4000-8000-00000000f00a");
        } catch (IllegalArgumentException e) {
            clienteUuid = UUID.fromString("00000000-0000-4000-8000-00000000f00a");
        }

        String nombreCliente = (request.comprobante().razonSocial() != null && !request.comprobante().razonSocial().isBlank())
                ? request.comprobante().razonSocial()
                : "Cliente Mostrador";

        String tipoDoc = "FACTURA".equals(request.comprobante().tipo()) ? "RUC" : "DNI";
        String numDoc = (request.comprobante().numeroDocumento() != null && !request.comprobante().numeroDocumento().isBlank())
                ? request.comprobante().numeroDocumento()
                : "00000000";

        VentasCrearPedidoRequest.Contacto contacto = new VentasCrearPedidoRequest.Contacto(
                clienteUuid,
                nombreCliente,
                tipoDoc,
                numDoc,
                "999999999",
                "mostrador@retail.g4"
        );

        List<VentasCrearPedidoRequest.Item> items = request.items().stream()
                .map(it -> new VentasCrearPedidoRequest.Item(
                        "PROD-" + it.sku(),
                        it.sku(),
                        "Prenda deportiva SKU " + it.sku(),
                        it.cantidad(),
                        it.precioFinal()
                ))
                .toList();

        VentasCrearPedidoRequest.Envio envio = new VentasCrearPedidoRequest.Envio(
                "TIENDA",
                BigDecimal.ZERO,
                nombreCliente,
                "Lima",
                "Lima",
                "Miraflores",
                "Tienda Mostrador",
                request.terminalPos(),
                "TIENDA-MIRAFLORES"
        );

        String medioPago = request.pago().medioPago();
        String refOperacion = (request.pago().referenciaOperacion() != null && !request.pago().referenciaOperacion().isBlank())
                ? request.pago().referenciaOperacion()
                : "OP-MOSTRADOR";

        VentasCrearPedidoRequest.Pago pago = new VentasCrearPedidoRequest.Pago(
                medioPago,
                "PEN",
                totalCalculado,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                totalCalculado,
                refOperacion,
                "EFECTIVO".equals(medioPago) ? totalCalculado : BigDecimal.ZERO,
                "TARJETA_POS".equals(medioPago) ? totalCalculado : BigDecimal.ZERO,
                ahora
        );

        return new VentasCrearPedidoRequest(
                "RETAIL",
                contacto,
                items,
                null,
                envio,
                pago
        );
    }

    /** Genera un ID de pedido unico con marca de tiempo y secuencia para trazabilidad. */
    private String generarPedidoId() {
        OffsetDateTime ahora = OffsetDateTime.now();
        return String.format("VNT-%d%02d%02d-%05d",
                ahora.getYear(), ahora.getMonthValue(), ahora.getDayOfMonth(),
                secuenciaPedido.getAndIncrement());
    }
}

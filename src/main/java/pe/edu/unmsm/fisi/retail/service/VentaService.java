package pe.edu.unmsm.fisi.retail.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import pe.edu.unmsm.fisi.retail.client.VentasClient;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;
import pe.edu.unmsm.fisi.retail.client.dto.VentasPedidoResponse;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.RegistroCobroRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.integracion.ventas.RegistroPedidosMostrador;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;
import pe.edu.unmsm.fisi.retail.repository.RegistroVentaRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 *   <li>Registrar el cobro en el turno de caja para el arqueo y corte Z (F8).</li>
 *   <li>Persistir la venta y sus items para soporte de cambios y devoluciones (F9).</li>
 *   <li>Registrar pedidos por documento para historial de compras (F6).</li>
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

    /** Servicio de caja para validar el turno real y registrar el cobro (F8). */
    private final CajaService cajaService;

    /** Indice de pedidos por documento del comprador, para la consulta historica (F6). */
    private final RegistroPedidosMostrador registroPedidos;

    /** Repositorio JDBC para persistencia local de ventas y comprobantes emitidos (F9). */
    private final RegistroVentaRepository registroVentaRepository;

    /** Puerto para consultar precios de catalogo (F2). */
    private final ProductosPort productosPort;

    /** Servicio de calculo de carrito para promociones y cupones (F4). */
    private final CarritoService carritoService;

    /** Repositorio de personal de tienda para resolver la tienda del vendedor. */
    private final PersonalTiendaRepository personalTiendaRepository;

    /** EntityManager para sincronizar cambios de JPA antes de operaciones JDBC. */
    private final EntityManager entityManager;

    /** Serializador JSON para la persistencia transaccional de idempotencia. */
    private final ObjectMapper json;

    /** Ejecutor transaccional para operaciones con bloqueo. */
    private final TransactionTemplate transaccion;

    /** Flag de integracion habilitada con Ventas G5. */
    private final boolean ventasHabilitado;

    /** Contador del correlativo de boleta (persistencia en memoria para tests sin base). */
    private final AtomicInteger correlativoBoleta = new AtomicInteger(1);
    private final AtomicInteger correlativoFactura = new AtomicInteger(1);

    /** Secuencia global para garantizar unicidad del pedidoId incluso en el mismo milisegundo. */
    private final AtomicInteger secuenciaPedido = new AtomicInteger(1);

    /** Cache de idempotencia en memoria (llave → respuesta ya generada). */
    private final Map<String, VentaResponse> cacheIdempotencia = new ConcurrentHashMap<>();

    /**
     * Mock Semana 8: conjunto de turnoIds abiertos en memoria.
     */
    private final Set<String> turnosAbiertos = ConcurrentHashMap.newKeySet();

    private record Lineas(List<VentaRequest.ItemVentaDto> items) {}

    /** Constructor sin argumentos para compatibilidad con tests unitarios existentes. */
    public VentaService() {
        this(null, null, null, null, null, null, null, null, null, null, true);
    }

    /** Constructor con el cliente de Ventas, sin caja ni registro de pedidos (tests de F5). */
    public VentaService(VentasClient ventasClient) {
        this(ventasClient, null, null, null, null, null, null, null, null, null, true);
    }

    /** Constructor con el servicio de caja, sin registro de pedidos (tests de F8). */
    public VentaService(VentasClient ventasClient, CajaService cajaService) {
        this(ventasClient, cajaService, null, null, null, null, null, null, null, null, true);
    }

    /** Constructor con el registro de pedidos, sin servicio de caja (tests de F6). */
    public VentaService(VentasClient ventasClient, RegistroPedidosMostrador registroPedidos) {
        this(ventasClient, null, registroPedidos, null, null, null, null, null, null, null, true);
    }

    /** Constructor con caja y registro de pedidos. */
    public VentaService(VentasClient ventasClient, CajaService cajaService, RegistroPedidosMostrador registroPedidos) {
        this(ventasClient, cajaService, registroPedidos, null, null, null, null, null, null, null, true);
    }

    /** Constructor principal con inyección opcional de dependencias. */
    @Autowired
    public VentaService(
            @Autowired(required = false) VentasClient ventasClient,
            @Autowired(required = false) CajaService cajaService,
            @Autowired(required = false) RegistroPedidosMostrador registroPedidos,
            @Autowired(required = false) RegistroVentaRepository registroVentaRepository,
            @Autowired(required = false) ProductosPort productosPort,
            @Autowired(required = false) CarritoService carritoService,
            @Autowired(required = false) PersonalTiendaRepository personalTiendaRepository,
            @Autowired(required = false) EntityManager entityManager,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) TransactionTemplate transactionTemplate,
            @Value("${retail.integracion.ventas.habilitado:true}") boolean ventasHabilitado
    ) {
        this.ventasClient = ventasClient;
        this.cajaService = cajaService;
        this.registroPedidos = registroPedidos;
        this.registroVentaRepository = registroVentaRepository;
        this.productosPort = productosPort;
        this.carritoService = carritoService;
        this.personalTiendaRepository = personalTiendaRepository;
        this.entityManager = entityManager;
        this.json = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.transaccion = transactionTemplate;
        this.ventasHabilitado = ventasHabilitado;
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
     * Overload de compatibilidad para tests unitarios existentes que no pasan usuario.
     */
    public VentaResponse registrarVenta(VentaRequest request, String idempotencyKey) {
        return registrarVenta(request, idempotencyKey, null);
    }

    /**
     * Procesa y registra una venta en mostrador (F5).
     *
     * @param request datos de la venta
     * @param idempotencyKey clave única de idempotencia
     * @param usuarioId identificador del vendedor autenticado
     * @return respuesta de la venta
     */
    public VentaResponse registrarVenta(VentaRequest request, String idempotencyKey, UUID usuarioId) {
        // 1. Validar turno y vendedor
        UUID turnoUuid = null;
        if (cajaService != null) {
            turnoUuid = turnoComoUuid(request.turnoId());
            cajaService.validarTurnoParaCobro(usuarioId, turnoUuid, request.terminalPos());
        } else {
            validarTurnoAbierto(request.turnoId());
        }

        // 2. Resolver precios unitarios si no vienen dados
        VentaRequest peticion = resolverLineas(request);
        BigDecimal totalCalculado = calcularTotal(peticion);
        validarMontoPago(peticion, totalCalculado);
        validarNormativaSunat(peticion, totalCalculado);

        if ("TARJETA_POS".equals(peticion.pago().medioPago())
                && peticion.pago().montoTarjeta() != null
                && totalCalculado.compareTo(peticion.pago().montoTarjeta()) != 0) {
            throw new ConflictoException("MONTO_TARJETA_DISTINTO", "El importe de tarjeta difiere del total calculado");
        }

        String tiendaId = resolverTienda(usuarioId);
        final UUID finalTurnoUuid = turnoUuid;

        // 3. Si hay persistencia en base (RegistroVentaRepository), maneja idempotencia con huella SHA-256
        if (registroVentaRepository != null && idempotencyKey != null) {
            String huella = calcularHuella(new Object[]{tiendaId, request});
            if (registroVentaRepository.operacion(idempotencyKey).isEmpty()) {
                registroVentaRepository.reservar(idempotencyKey, usuarioId, huella,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        serializar(new Lineas(peticion.items())));
            }

            if (transaccion != null) {
                return transaccion.execute(status ->
                        ejecutarCobro(peticion, idempotencyKey, usuarioId, finalTurnoUuid, tiendaId, totalCalculado, huella));
            } else {
                return ejecutarCobro(peticion, idempotencyKey, usuarioId, finalTurnoUuid, tiendaId, totalCalculado, huella);
            }
        }

        // 4. En memoria: validar idempotencia
        if (idempotencyKey != null && cacheIdempotencia.containsKey(idempotencyKey)) {
            log.info("Idempotencia activada para llave={}", idempotencyKey);
            return cacheIdempotencia.get(idempotencyKey);
        }

        return ejecutarCobro(peticion, idempotencyKey, usuarioId, finalTurnoUuid, tiendaId, totalCalculado, null);
    }

    private String resolverTienda(UUID usuarioId) {
        if (usuarioId != null && personalTiendaRepository != null) {
            return personalTiendaRepository.findByUsuarioIdAndActivoTrue(usuarioId)
                    .map(PersonalTienda::getTiendaId)
                    .orElse("TIENDA-MIRAFLORES");
        }
        return "TIENDA-MIRAFLORES";
    }

    private VentaResponse ejecutarCobro(VentaRequest peticion, String idempotencyKey, UUID usuarioId,
                                        UUID turnoUuid, String tiendaId, BigDecimal totalCalculado, String huella) {
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);

        if (registroVentaRepository != null && idempotencyKey != null) {
            var operacion = registroVentaRepository.bloquearOperacion(idempotencyKey);
            if ((usuarioId != null && !usuarioId.equals(operacion.usuarioId()))
                    || (huella != null && !huella.equals(operacion.huella()))) {
                throw new ConflictoException("IDEMPOTENCY_CONFLICT", "La clave ya corresponde a otra venta");
            }
            if (operacion.respuesta() != null) {
                return leer(operacion.respuesta(), VentaResponse.class);
            }
            if (operacion.fecha() != null) {
                ahora = operacion.fecha().withOffsetSameInstant(ZoneOffset.UTC);
            }
        }

        String pedidoId;
        String estadoPedido;

        if (ventasClient != null && ventasHabilitado) {
            VentasCrearPedidoRequest pedidoRequest = armarPedidoVentasRequest(peticion, totalCalculado, ahora, tiendaId);
            Optional<VentasPedidoResponse> respuestaVentas = ventasClient.crearPedido(pedidoRequest, idempotencyKey);
            if (respuestaVentas.isPresent() && respuestaVentas.get().pedidoId() != null && !respuestaVentas.get().pedidoId().isBlank()) {
                pedidoId = respuestaVentas.get().pedidoId();
                estadoPedido = respuestaVentas.get().estado() != null ? respuestaVentas.get().estado() : "PAGADO";
            } else if (registroVentaRepository != null) {
                throw new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE",
                        "Ventas no confirmó el cobro. Reintenta la misma operación");
            } else {
                pedidoId = generarPedidoId();
                estadoPedido = "PAGADO";
            }
        } else {
            pedidoId = generarPedidoId();
            estadoPedido = "PAGADO";
        }

        boolean factura = "FACTURA".equals(peticion.comprobante().tipo());
        String serie = factura ? "F001" : "B001";
        String correlativo;
        if (registroVentaRepository != null) {
            int corr = registroVentaRepository.siguienteCorrelativo(serie);
            correlativo = String.format(Locale.ROOT, "%08d", corr);
        } else {
            int numero = factura ? correlativoFactura.getAndIncrement() : correlativoBoleta.getAndIncrement();
            correlativo = String.format(Locale.ROOT, "%08d", numero);
        }

        String ventaId = UUID.randomUUID().toString();
        VentaResponse respuesta = emitirRespuesta(peticion, totalCalculado, ahora, pedidoId, ventaId,
                serie, correlativo, estadoPedido);

        // 10. Registrar cobro en el turno de caja para Corte Z (F8)
        if (cajaService != null) {
            String medioPago = peticion.pago().medioPago();
            BigDecimal recibido = "EFECTIVO".equals(medioPago)
                    ? peticion.pago().montoRecibidoEfectivo()
                    : totalCalculado;
            cajaService.registrarCobro(usuarioId, turnoUuid, peticion.terminalPos(),
                    new RegistroCobroRequest(
                            idempotencyKey,
                            medioPago,
                            totalCalculado,
                            recibido,
                            pedidoId,
                            serie,
                            correlativo
                    ));
        }

        // 11. Persistir venta en RET_VENTA para F9 (cambios y vales)
        if (registroVentaRepository != null && idempotencyKey != null) {
            if (entityManager != null && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                entityManager.flush();
            }
            registroVentaRepository.guardar(idempotencyKey, turnoUuid, tiendaId, respuesta,
                    peticion.items(), serializar(respuesta));
        }

        // 12. Registrar en historial de compras de cliente (F6)
        if (registroPedidos != null) {
            registroPedidos.registrar(peticion.comprobante().numeroDocumento(), pedidoId);
        }

        if (idempotencyKey != null) {
            cacheIdempotencia.put(idempotencyKey, respuesta);
        }
        log.info("Venta registrada: ventaId={} pedidoId={} serie={} correlativo={} terminal={} total={}",
                ventaId, pedidoId, serie, correlativo, peticion.terminalPos(), totalCalculado);

        return respuesta;
    }

    VentaResponse emitirRespuesta(VentaRequest request, BigDecimal totalCalculado, OffsetDateTime ahora,
                                  String pedidoId, String ventaId, String serie, String correlativo, String estadoPedido) {
        BigDecimal igv = totalCalculado
                .multiply(TASA_IGV)
                .divide(BigDecimal.ONE.add(TASA_IGV), 2, RoundingMode.HALF_UP);
        BigDecimal subtotal = totalCalculado.subtract(igv).setScale(2, RoundingMode.HALF_UP);

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

        String doc = request.comprobante().numeroDocumento() != null
                ? request.comprobante().numeroDocumento() : "";
        String qr = String.join("|",
                RUC_EMPRESA_MOCK, serie, correlativo,
                igv.toPlainString(), totalCalculado.toPlainString(), doc);

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

        VentaResponse.PagoResumen pagoResumen = new VentaResponse.PagoResumen(
                medioPago, montoRecibido, vuelto
        );

        VentaResponse.TicketRegalo ticketRegalo = null;
        if (request.emitirTicketRegalo()) {
            ticketRegalo = new VentaResponse.TicketRegalo(
                    "CANJE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    ahora.plusDays(30)
            );
        }

        return new VentaResponse(
                ventaId, pedidoId, estadoPedido, pagoResumen, comprobante, ticketRegalo, request.items()
        );
    }

    private VentaRequest resolverLineas(VentaRequest solicitud) {
        boolean requiereCotizar = solicitud.items().stream().anyMatch(it -> it.precioFinal() == null);
        if (requiereCotizar && productosPort != null && carritoService != null) {
            List<VentaRequest.ItemVentaDto> items = cotizar(solicitud);
            return new VentaRequest(solicitud.terminalPos(), solicitud.turnoId(), solicitud.clienteId(), items,
                    solicitud.pago(), solicitud.comprobante(), solicitud.emitirTicketRegalo(),
                    solicitud.cupon(), solicitud.evaluacionId());
        }
        return solicitud;
    }

    private List<VentaRequest.ItemVentaDto> cotizar(VentaRequest solicitud) {
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        solicitud.items().forEach(item -> cantidades.merge(item.sku(), item.cantidad(), Math::addExact));
        var items = cantidades.entrySet().stream().map(item -> new CalculoCarritoRequest.ItemCarritoCalculoDto(
                item.getKey(), item.getValue(), productosPort.consultarPrecio(item.getKey())
                .map(precio -> precio.precioEfectivo()).filter(precio -> precio.signum() >= 0)
                .orElseThrow(() -> new ReglaNegocioException("PRECIO_NO_DISPONIBLE",
                        "No hay precio vigente para " + item.getKey())))).toList();
        return carritoService.calcularTotales(new CalculoCarritoRequest(items, solicitud.cupon())).lineas().stream()
                .map(linea -> {
                    try {
                        return new VentaRequest.ItemVentaDto(linea.sku(), linea.cantidad(),
                                linea.precioFinal().divide(BigDecimal.valueOf(linea.cantidad()), 2, RoundingMode.UNNECESSARY));
                    } catch (ArithmeticException e) {
                        throw new ReglaNegocioException("MONTO_NO_DETERMINABLE",
                                "El descuento no se puede atribuir por unidad a " + linea.sku());
                    }
                }).toList();
    }

    private String calcularHuella(Object dato) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serializar(dato).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String serializar(Object dato) {
        try {
            return json.writeValueAsString(dato);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo procesar la venta", e);
        }
    }

    private <T> T leer(String dato, Class<T> tipo) {
        try {
            return json.readValue(dato, tipo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo recuperar la venta registrada", e);
        }
    }

    // ── Metodos privados de calculo ─────────────────────────────────────────

    void validarTurnoAbierto(String turnoId) {
        if (!turnosAbiertos.contains(turnoId)) {
            throw new ReglaNegocioException(
                    "TURNO_NO_ABIERTO",
                    "No hay un turno de caja abierto con id " + turnoId +
                    ". Debe abrir un turno antes de registrar ventas."
            );
        }
    }

    private UUID turnoComoUuid(String turnoId) {
        try {
            return UUID.fromString(turnoId);
        } catch (IllegalArgumentException e) {
            throw new RecursoNoEncontradoException("TURNO_NO_ENCONTRADO",
                    "El turno de caja indicado no existe");
        }
    }

    BigDecimal calcularTotal(VentaRequest request) {
        return request.items().stream()
                .map(item -> item.precioFinal().multiply(BigDecimal.valueOf(item.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

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

    VentasCrearPedidoRequest armarPedidoVentasRequest(VentaRequest request, BigDecimal totalCalculado,
                                                     OffsetDateTime ahora, String tiendaId) {
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
                tiendaId
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

    private String generarPedidoId() {
        OffsetDateTime ahora = OffsetDateTime.now();
        return String.format("VNT-%d%02d%02d-%05d",
                ahora.getYear(), ahora.getMonthValue(), ahora.getDayOfMonth(),
                secuenciaPedido.getAndIncrement());
    }
}

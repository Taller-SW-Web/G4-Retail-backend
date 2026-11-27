package pe.edu.unmsm.fisi.retail.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.CambioDirectoRequest;
import pe.edu.unmsm.fisi.retail.dto.CambioDirectoResponse;
import pe.edu.unmsm.fisi.retail.dto.RegistroCobroRequest;
import pe.edu.unmsm.fisi.retail.dto.ValidarCambioResponse;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.ParametroInvalidoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.boletas.BoletaEmitida;
import pe.edu.unmsm.fisi.retail.integracion.boletas.BoletasEmitidasPort;
import pe.edu.unmsm.fisi.retail.integracion.cambios.PedidosCambioPort;
import pe.edu.unmsm.fisi.retail.integracion.cambios.ResultadoPedidoCambio;
import pe.edu.unmsm.fisi.retail.integracion.cambios.SkusEnCuarentenaPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.model.EstadoAprobacionCambio;
import pe.edu.unmsm.fisi.retail.model.EstadoCobroTurno;
import pe.edu.unmsm.fisi.retail.model.EstadoInventarioCambio;
import pe.edu.unmsm.fisi.retail.model.MotivoCambio;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.model.SolicitudCambio;
import pe.edu.unmsm.fisi.retail.model.SolicitudCambioItem;
import pe.edu.unmsm.fisi.retail.model.TipoCambio;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;
import pe.edu.unmsm.fisi.retail.repository.RegistroVentaRepository;
import pe.edu.unmsm.fisi.retail.repository.SolicitudCambioItemRepository;
import pe.edu.unmsm.fisi.retail.repository.SolicitudCambioRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cambio directo de prenda en mostrador (F9, contrato 5.9).
 *
 * <p>Orden de las comprobaciones al registrar, de lo mas barato a lo mas caro y de modo que el
 * vendedor reciba primero el motivo que puede resolver: idempotencia, vendedor, turno, boleta, plazo,
 * prenda devuelta y unidades, inspeccion, prendas nuevas y su valor. Solo al final se llama a Ventas.</p>
 *
 * <p>Concurrencia: la boleta de origen se lee con bloqueo, asi que dos cambios a la vez sobre la misma
 * boleta se atienden uno tras otro y el segundo ya ve las unidades que consumio el primero.</p>
 */
@Slf4j
@Service
public class CambioServiceImpl implements CambioService {

    private static final Set<String> ESTADOS_APTOS = Set.of("PAGADO", "ENTREGADO");
    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final BoletasEmitidasPort boletas;
    private final PoliticaPlazoCambio politicaPlazo;
    private final PoliticaInspeccionPrenda politicaInspeccion;
    private final PersonalTiendaRepository personalTiendaRepository;
    private final SolicitudCambioRepository solicitudes;
    private final SolicitudCambioItemRepository items;
    private final CajaService cajaService;
    private final VentaService ventaService;
    private final CarritoService carritoService;
    private final ProductosPort productos;
    private final CatalogoService catalogo;
    private final SkusEnCuarentenaPort cuarentena;
    private final PedidosCambioPort pedidos;
    private final NumeradorConstanciaCambio numerador;
    private final RegistroVentaRepository registroVentas;
    private final ObjectMapper json;
    private final EntityManager entityManager;
    private final String metodoPagoCredito;
    private final Clock reloj;

    @Autowired
    @SuppressWarnings("java:S107") // el cambio directo orquesta caja, ventas, catalogo y F12
    public CambioServiceImpl(BoletasEmitidasPort boletas,
                             PoliticaPlazoCambio politicaPlazo,
                             PoliticaInspeccionPrenda politicaInspeccion,
                             PersonalTiendaRepository personalTiendaRepository,
                             SolicitudCambioRepository solicitudes,
                             SolicitudCambioItemRepository items,
                             CajaService cajaService,
                             VentaService ventaService,
                             CarritoService carritoService,
                             ProductosPort productos,
                             CatalogoService catalogo,
                             SkusEnCuarentenaPort cuarentena,
                             PedidosCambioPort pedidos,
                             NumeradorConstanciaCambio numerador,
                             RegistroVentaRepository registroVentas,
                             ObjectMapper json,
                             EntityManager entityManager,
                             @Value("${retail.ventas.metodo-pago-credito:CREDITO_CAMBIO}") String metodoPagoCredito,
                             Clock reloj) {
        this.boletas = boletas;
        this.politicaPlazo = politicaPlazo;
        this.politicaInspeccion = politicaInspeccion;
        this.personalTiendaRepository = personalTiendaRepository;
        this.solicitudes = solicitudes;
        this.items = items;
        this.cajaService = cajaService;
        this.ventaService = ventaService;
        this.carritoService = carritoService;
        this.productos = productos;
        this.catalogo = catalogo;
        this.cuarentena = cuarentena;
        this.pedidos = pedidos;
        this.numerador = numerador;
        this.registroVentas = registroVentas;
        this.json = json;
        this.entityManager = entityManager;
        this.metodoPagoCredito = metodoPagoCredito;
        this.reloj = reloj;
    }

    // ---------------------------------------------------------------- RF-23: validacion de la boleta

    @Override
    @Transactional(readOnly = true)
    public ValidarCambioResponse validar(UUID usuarioId, String boletaNumero) {
        BoletaEmitida boleta = boletaApta(boletaNumero, vendedorActivo(usuarioId).getTiendaId(), false);
        PoliticaPlazoCambio.Evaluacion plazo = politicaPlazo.evaluar(boleta.fechaEmision());

        List<ValidarCambioResponse.ItemCambiable> cambiables = boleta.items().stream()
                .map(item -> new ValidarCambioResponse.ItemCambiable(
                        item.sku(), item.descripcion(), item.precioPagado(), item.cantidad(),
                        disponibles(boleta.numero(), item)))
                .toList();

        return new ValidarCambioResponse(boleta.numero(), boleta.pedidoId(), boleta.fechaEmision().toString(),
                plazo.diasTranscurridos(), plazo.plazoDias(), plazo.plazoValido(), cambiables);
    }

    // ---------------------------------------------------------------- RF-24 y RF-25: cambio directo

    @Override
    @Transactional
    public CambioDirectoResponse registrar(UUID usuarioId, String clave, CambioDirectoRequest peticion) {
        PersonalTienda vendedor = vendedorActivo(usuarioId);
        String tienda = vendedor.getTiendaId();
        String huella = huella(tienda, peticion);

        Optional<SolicitudCambio> repetida = solicitudes.findByIdempotencyKey(clave);
        if (repetida.isPresent()) {
            return respuestaRepetida(usuarioId, huella, repetida.get());
        }
        if (peticion.motivo() == MotivoCambio.FALLA_FABRICA) {
            throw new ReglaNegocioException("GARANTIA_REQUIERE_POSTVENTA",
                    "Deriva la falla de fábrica a Postventa para revisión de garantía");
        }

        UUID turno = turnoComoUuid(peticion.turnoId());
        cajaService.validarTurnoParaCobro(usuarioId, turno, peticion.terminalPos());

        // Bloquea la boleta: un segundo cambio simultaneo espera y luego ve las unidades ya consumidas.
        BoletaEmitida boleta = boletaApta(peticion.boletaNumero(), tienda, true);
        repetida = solicitudes.findByIdempotencyKey(clave);
        if (repetida.isPresent()) {
            return respuestaRepetida(usuarioId, huella, repetida.get());
        }
        if (!politicaPlazo.evaluar(boleta.fechaEmision()).plazoValido()) {
            throw new ReglaNegocioException("PLAZO_VENCIDO",
                    "El plazo de cambio de la boleta " + boleta.numero() + " ya vencio");
        }

        CambioDirectoRequest.Devuelto devuelto = peticion.devuelto();
        BoletaEmitida.Item comprado = boleta.items().stream()
                .filter(i -> i.sku().equals(devuelto.sku()))
                .findFirst()
                .orElseThrow(() -> new ReglaNegocioException("PRENDA_NO_INCLUIDA",
                        "La prenda " + devuelto.sku() + " no figura en la boleta " + boleta.numero()));
        int disponibles = disponibles(boleta.numero(), comprado);
        if (devuelto.cantidad() > disponibles) {
            throw new ReglaNegocioException("CANTIDAD_NO_DISPONIBLE",
                    "Solo quedan " + disponibles + " unidades de " + comprado.sku() + " por cambiar con esta boleta");
        }

        var inspeccion = politicaInspeccion.evaluar(peticion.motivo(), devuelto.inspeccion());
        if (!inspeccion.aprobado()) {
            throw new ReglaNegocioException("INSPECCION_NO_APTA",
                    "La prenda no cumple la inspeccion: " + String.join("; ", inspeccion.observaciones()));
        }

        BigDecimal credito = escala(comprado.precioPagado().multiply(BigDecimal.valueOf(devuelto.cantidad())));
        List<VentaRequest.ItemVentaDto> nuevas = cotizarNuevas(tienda, peticion.nuevos());
        BigDecimal totalNuevo = escala(nuevas.stream()
                .map(l -> l.precioFinal().multiply(BigDecimal.valueOf(l.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        TipoCambio tipo = ClasificadorCambio.clasificar(credito, totalNuevo, esMismoModelo(devuelto, nuevas));
        BigDecimal diferencia = tipo == TipoCambio.MAYOR_VALOR ? escala(totalNuevo.subtract(credito)) : CERO;
        OffsetDateTime ahora = OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC);

        SolicitudCambio solicitud = SolicitudCambio.builder()
                .cajaSesionId(turno)
                .tiendaId(tienda)
                .ventaId(boleta.ventaId())
                .vendedorId(usuarioId)
                .boletaNumero(boleta.numero())
                .pedidoIdOrigen(boleta.pedidoId())
                .varianteSku(comprado.sku())
                .clienteId(peticion.clienteId())
                .cantidad(devuelto.cantidad())
                .motivoCambio(peticion.motivo())
                .inspeccionEtiquetas(devuelto.inspeccion().etiquetasIntactas())
                .inspeccionSinUso(devuelto.inspeccion().sinSignosUso())
                .inspeccionEmpaque(devuelto.inspeccion().empaqueOriginal())
                .estadoAprobacion(EstadoAprobacionCambio.APROBADO_EN_TIENDA)
                .montoAcreditado(credito)
                .idempotencyKey(clave)
                .fechaInspeccion(ahora)
                .tipoCambio(tipo)
                .numeroConstancia(numerador.siguiente(tienda, ahora.toInstant()))
                .montoCredito(credito)
                .totalNuevo(totalNuevo)
                .diferencia(diferencia)
                .huella(huella)
                .build();

        VentaRequest ventaNueva = ventaDeLasNuevas(peticion, nuevas, tipo);
        VentaResponse.PagoResumen pago = null;
        VentaResponse.ComprobanteEmitido comprobante = null;
        if (tipo == TipoCambio.MAYOR_VALOR) {
            validarPagoDiferencia(ventaNueva, diferencia, totalNuevo);
        }

        VentasCrearPedidoRequest pedido = pedidoParaVentas(ventaNueva, totalNuevo, credito, diferencia, ahora,
                tienda, boleta, devuelto);
        ResultadoPedidoCambio resultado = pedidos.enviar(pedido, clave);
        if (resultado instanceof ResultadoPedidoCambio.Rechazado rechazo) {
            throw new ReglaNegocioException("PEDIDO_NO_APTO",
                    "Ventas rechazo el cambio (" + rechazo.codigo() + "): " + rechazo.detalle());
        }

        if (resultado instanceof ResultadoPedidoCambio.Confirmado confirmado) {
            solicitud.setPedidoIdNuevo(confirmado.pedidoId());
            solicitud.setEstadoInventario(EstadoInventarioCambio.CONFIRMADO);
        } else if (tipo == TipoCambio.MAYOR_VALOR) {
            // Como en F5: sin pedido confirmado no se emite boleta ni se cobra. La clave queda libre para reintentar.
            throw new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE",
                    "Ventas no confirmó el pedido del cambio. Reintenta la misma operación");
        } else {
            // Mismo valor: el cambio no se revierte; el job reenvia el pedido con la misma clave.
            solicitud.setEstadoInventario(EstadoInventarioCambio.PENDIENTE);
            solicitud.setPedidoPendiente(serializar(pedido));
            solicitud.setIntentosEnvio(1);
            log.warn("Pedido del cambio {} pendiente en Ventas: {}", clave,
                    ((ResultadoPedidoCambio.NoDisponible) resultado).motivo());
        }

        if (tipo == TipoCambio.MAYOR_VALOR) {
            VentaResponse boletaNueva = emitirBoleta(ventaNueva, totalNuevo, diferencia, ahora, solicitud.getPedidoIdNuevo(),
                    tienda, turno);
            solicitud.setVentaNuevaId(UUID.fromString(boletaNueva.ventaId()));
            comprobante = boletaNueva.comprobante();
            pago = boletaNueva.pago();
            cajaService.registrarCobro(usuarioId, turno, peticion.terminalPos(), new RegistroCobroRequest(
                    clave, pago.medioPago(), diferencia, pago.montoRecibido(), solicitud.getPedidoIdNuevo(),
                    comprobante.serie(), comprobante.correlativo()), EstadoCobroTurno.CONFIRMADO);
        }

        solicitud = solicitudes.save(solicitud);
        UUID solicitudId = solicitud.getIdSolicitud();
        items.saveAll(nuevas.stream().map(l -> SolicitudCambioItem.builder()
                .solicitudId(solicitudId).sku(l.sku()).cantidad(l.cantidad()).precioUnitario(l.precioFinal())
                .build()).toList());

        CambioDirectoResponse respuesta = new CambioDirectoResponse(
                solicitudId.toString(), solicitud.getNumeroConstancia(), tipo.name(), boleta.numero(),
                credito, totalNuevo, diferencia, pago, comprobante, solicitud.getPedidoIdNuevo(),
                solicitud.getEstadoInventario().name(), ahora.toInstant().toString(),
                new CambioDirectoResponse.Prenda(comprado.sku(), devuelto.cantidad(), comprado.precioPagado()),
                nuevas.stream().map(l -> new CambioDirectoResponse.Prenda(l.sku(), l.cantidad(), l.precioFinal())).toList());
        solicitud.setRespuesta(serializar(respuesta));
        return respuesta;
    }

    // ---------------------------------------------------------------- job: pedidos pendientes

    @Override
    @Transactional
    public int reintentarPedidosPendientes() {
        int confirmados = 0;
        for (SolicitudCambio solicitud : solicitudes.findTop50ByEstadoInventarioOrderByFechaInspeccionAsc(
                EstadoInventarioCambio.PENDIENTE)) {
            VentasCrearPedidoRequest pedido = leer(solicitud.getPedidoPendiente(), VentasCrearPedidoRequest.class);
            ResultadoPedidoCambio resultado = pedidos.enviar(pedido, solicitud.getIdempotencyKey());
            solicitud.setIntentosEnvio(solicitud.getIntentosEnvio() + 1);
            if (resultado instanceof ResultadoPedidoCambio.Confirmado confirmado) {
                solicitud.setPedidoIdNuevo(confirmado.pedidoId());
                solicitud.setEstadoInventario(EstadoInventarioCambio.CONFIRMADO);
                solicitud.setPedidoPendiente(null);
                CambioDirectoResponse anterior = leer(solicitud.getRespuesta(), CambioDirectoResponse.class);
                solicitud.setRespuesta(serializar(conPedido(anterior, confirmado.pedidoId())));
                confirmados++;
            } else if (resultado instanceof ResultadoPedidoCambio.Rechazado rechazo) {
                // No se reintenta a ciegas: queda PENDIENTE para revision, con el motivo en el log.
                log.error("Ventas rechazo el pedido pendiente del cambio {}: {} {}",
                        solicitud.getNumeroConstancia(), rechazo.codigo(), rechazo.detalle());
            }
        }
        return confirmados;
    }

    // ---------------------------------------------------------------- piezas del registro

    /** Prendas nuevas con el precio vigente y sin cupon, como POST /carritos/calcular. */
    private List<VentaRequest.ItemVentaDto> cotizarNuevas(String tienda, List<CambioDirectoRequest.PrendaNueva> nuevos) {
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        nuevos.forEach(n -> cantidades.merge(n.sku(), n.cantidad(), Math::addExact));
        cantidades.keySet().forEach(sku -> {
            if (cuarentena.enCuarentena(tienda, sku)) {
                throw new ReglaNegocioException("SKU_EN_CUARENTENA",
                        "La prenda " + sku + " esta en cuarentena y no puede entregarse en un cambio");
            }
        });
        var lineas = cantidades.entrySet().stream().map(e -> new CalculoCarritoRequest.ItemCarritoCalculoDto(
                e.getKey(), e.getValue(), productos.consultarPrecio(e.getKey())
                .map(precio -> precio.precioEfectivo()).filter(precio -> precio.signum() >= 0)
                .orElseThrow(() -> new ReglaNegocioException("PRECIO_NO_DISPONIBLE",
                        "No hay precio vigente para " + e.getKey())))).toList();
        return carritoService.calcularTotales(new CalculoCarritoRequest(lineas, null)).lineas().stream()
                .map(linea -> {
                    try {
                        return new VentaRequest.ItemVentaDto(linea.sku(), linea.cantidad(), linea.precioFinal()
                                .divide(BigDecimal.valueOf(linea.cantidad()), 2, RoundingMode.UNNECESSARY));
                    } catch (ArithmeticException e) {
                        throw new ReglaNegocioException("MONTO_NO_DETERMINABLE",
                                "El descuento no se puede atribuir por unidad a " + linea.sku());
                    }
                }).toList();
    }

    /** Mismo producto que la devuelta (otra talla o color) y en la misma cantidad. */
    private boolean esMismoModelo(CambioDirectoRequest.Devuelto devuelto, List<VentaRequest.ItemVentaDto> nuevas) {
        int unidades = nuevas.stream().mapToInt(VentaRequest.ItemVentaDto::cantidad).sum();
        if (unidades != devuelto.cantidad()) {
            return false;
        }
        Optional<String> producto = catalogo.productoDelSku(devuelto.sku());
        return producto.isPresent() && nuevas.stream()
                .allMatch(n -> catalogo.productoDelSku(n.sku()).equals(producto));
    }

    /**
     * Las prendas nuevas como una venta de F5, para reutilizar sus reglas de cobro y su pedido. En mismo
     * valor no hay pago ni comprobante: se usan valores neutros que no se cobran ni se imprimen.
     */
    private VentaRequest ventaDeLasNuevas(CambioDirectoRequest peticion, List<VentaRequest.ItemVentaDto> nuevas,
                                          TipoCambio tipo) {
        VentaRequest.PagoDto pago = peticion.pagoDiferencia();
        VentaRequest.ComprobanteDto comprobante = peticion.comprobante();
        if (tipo == TipoCambio.MAYOR_VALOR) {
            if (pago == null) {
                throw new ParametroInvalidoException("PARAMETRO_INVALIDO", "pagoDiferencia",
                        "El cambio es de mayor valor: indica como se paga la diferencia");
            }
            if (comprobante == null) {
                throw new ParametroInvalidoException("PARAMETRO_INVALIDO", "comprobante",
                        "El cambio es de mayor valor: indica el comprobante de la diferencia");
            }
        } else {
            pago = new VentaRequest.PagoDto("EFECTIVO", null, null, null);
            comprobante = new VentaRequest.ComprobanteDto("BOLETA", null, null);
        }
        String cliente = peticion.clienteId() == null ? null : peticion.clienteId().toString();
        return new VentaRequest(peticion.terminalPos(), peticion.turnoId(), cliente, nuevas, pago, comprobante,
                false, null, null);
    }

    /** Las reglas de cobro de F5, aplicadas a la diferencia; la normativa SUNAT, al total de la boleta. */
    private void validarPagoDiferencia(VentaRequest venta, BigDecimal diferencia, BigDecimal totalNuevo) {
        ventaService.validarMontoPago(venta, diferencia);
        ventaService.validarNormativaSunat(venta, totalNuevo);
        if ("TARJETA_POS".equals(venta.pago().medioPago())
                && (venta.pago().montoTarjeta() == null || diferencia.compareTo(venta.pago().montoTarjeta()) != 0)) {
            throw new ConflictoException("MONTO_TARJETA_DISTINTO", "El importe de tarjeta difiere de la diferencia a cobrar");
        }
    }

    /** Pedido para Ventas con el credito del cambio (acuerdo con G5 del 26/11). */
    private VentasCrearPedidoRequest pedidoParaVentas(VentaRequest venta, BigDecimal totalNuevo, BigDecimal credito,
                                                      BigDecimal diferencia, OffsetDateTime ahora, String tienda,
                                                      BoletaEmitida boleta, CambioDirectoRequest.Devuelto devuelto) {
        VentasCrearPedidoRequest base = ventaService.armarPedidoVentasRequest(venta, totalNuevo, ahora, tienda);
        boolean hayDiferencia = diferencia.signum() > 0;
        String medio = venta.pago().medioPago();
        String referencia = hayDiferencia && "TARJETA_POS".equals(medio) ? venta.pago().referenciaOperacion() : null;
        VentasCrearPedidoRequest.Pago pago = new VentasCrearPedidoRequest.Pago(
                metodoPagoCredito, "PEN", totalNuevo, BigDecimal.ZERO, BigDecimal.ZERO, totalNuevo,
                referencia, boleta.numero(),
                hayDiferencia && "EFECTIVO".equals(medio) ? diferencia : null,
                hayDiferencia && "TARJETA_POS".equals(medio) ? diferencia : null,
                ahora, credito, boleta.pedidoId(),
                List.of(new VentasCrearPedidoRequest.ItemDevuelto(devuelto.sku(), devuelto.cantidad())));
        return new VentasCrearPedidoRequest(base.canal(), base.contacto(), base.items(), null, base.envio(), pago);
    }

    /** Boleta de las prendas nuevas por el total nuevo; el pago es solo la diferencia. Se guarda en RET_VENTA. */
    private VentaResponse emitirBoleta(VentaRequest venta, BigDecimal totalNuevo, BigDecimal diferencia,
                                       OffsetDateTime ahora, String pedidoId, String tienda, UUID turno) {
        String serie = "FACTURA".equals(venta.comprobante().tipo()) ? "F001" : "B001";
        String correlativo = String.format(Locale.ROOT, "%08d", registroVentas.siguienteCorrelativo(serie));
        VentaResponse emitida = ventaService.emitirRespuesta(venta, totalNuevo, ahora, pedidoId,
                UUID.randomUUID().toString(), serie, correlativo, "PAGADO");

        String medio = venta.pago().medioPago();
        BigDecimal recibido = "EFECTIVO".equals(medio) ? escala(venta.pago().montoRecibidoEfectivo()) : diferencia;
        var pago = new VentaResponse.PagoResumen(medio, recibido, escala(recibido.subtract(diferencia)));
        VentaResponse boleta = new VentaResponse(emitida.ventaId(), pedidoId, "PAGADO", pago, emitida.comprobante(),
                null, venta.items());
        // RET_VENTA se escribe por JDBC: antes se vuelcan los cambios JPA pendientes, como el turno de caja.
        entityManager.flush();
        registroVentas.insertarVenta(turno, tienda, boleta, venta.items());
        return boleta;
    }

    // ---------------------------------------------------------------- auxiliares

    private PersonalTienda vendedorActivo(UUID usuarioId) {
        var vendedor = personalTiendaRepository.findByUsuarioIdAndActivoTrue(usuarioId)
                .orElseThrow(() -> new AccesoDenegadoException("FORBIDDEN_USUARIO_SIN_TIENDA",
                        "El usuario autenticado no tiene un perfil activo en la tienda"));
        if (vendedor.getPerfilTienda() == PerfilTienda.CAJERO) {
            throw new AccesoDenegadoException("ROL_NO_PERMITIDO", "El perfil no puede gestionar cambios");
        }
        return vendedor;
    }

    /** La boleta de la tienda existe y su pedido admite cambios. */
    private BoletaEmitida boletaApta(String boletaNumero, String tienda, boolean bloquear) {
        BoletaEmitida boleta = boletas.buscarPorNumero(boletaNumero, tienda, bloquear)
                .orElseThrow(() -> new RecursoNoEncontradoException("COMPROBANTE_NO_ENCONTRADO",
                        "No existe el comprobante " + boletaNumero));
        if (!ESTADOS_APTOS.contains(boleta.estado())) {
            throw new ReglaNegocioException("PEDIDO_NO_APTO",
                    "El pedido de la boleta " + boletaNumero + " esta " + boleta.estado()
                            + "; solo se cambian compras pagadas o entregadas");
        }
        return boleta;
    }

    private int disponibles(String boletaNumero, BoletaEmitida.Item item) {
        long cambiadas = solicitudes.unidadesYaCambiadas(
                boletaNumero, item.sku(), EstadoAprobacionCambio.APROBADO_EN_TIENDA);
        return (int) Math.max(0, item.cantidad() - cambiadas);
    }

    private static UUID turnoComoUuid(String turnoId) {
        try {
            return UUID.fromString(turnoId);
        } catch (IllegalArgumentException e) {
            throw new ConflictoException("TURNO_NO_ABIERTO", "El turno de caja indicado no es valido");
        }
    }

    /**
     * La misma clave con la misma peticion devuelve el cambio original, sin consumir mas unidades; con
     * otra peticion o de otro vendedor es un error del cliente, no un reintento.
     */
    private CambioDirectoResponse respuestaRepetida(UUID usuario, String huella, SolicitudCambio solicitud) {
        if (solicitud.getRespuesta() == null || !usuario.equals(solicitud.getVendedorId())
                || !Objects.equals(huella, solicitud.getHuella())) {
            throw new ConflictoException("IDEMPOTENCY_CONFLICT",
                    "La clave de idempotencia ya se uso con una solicitud de cambio distinta");
        }
        return leer(solicitud.getRespuesta(), CambioDirectoResponse.class);
    }

    private static CambioDirectoResponse conPedido(CambioDirectoResponse r, String pedidoId) {
        return new CambioDirectoResponse(r.cambioId(), r.numeroConstancia(), r.tipoCambio(), r.boletaOrigen(),
                r.montoCredito(), r.totalNuevo(), r.diferencia(), r.pago(), r.comprobante(), pedidoId,
                EstadoInventarioCambio.CONFIRMADO.name(), r.fechaCambio(), r.devuelto(), r.nuevos());
    }

    private String huella(String tienda, CambioDirectoRequest peticion) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serializar(new Object[] {tienda, peticion}).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String serializar(Object dato) {
        try {
            return json.writeValueAsString(dato);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo registrar el cambio", e);
        }
    }

    private <T> T leer(String dato, Class<T> tipo) {
        try {
            return json.readValue(dato, tipo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo recuperar el cambio registrado", e);
        }
    }

    private static BigDecimal escala(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.HALF_UP);
    }
}

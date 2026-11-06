package pe.edu.unmsm.fisi.retail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.dto.InspeccionPrenda;
import pe.edu.unmsm.fisi.retail.dto.RegistroCambioRequest;
import pe.edu.unmsm.fisi.retail.dto.ValeCompraResponse;
import pe.edu.unmsm.fisi.retail.dto.ValidarCambioResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.integracion.boletas.BoletaEmitida;
import pe.edu.unmsm.fisi.retail.integracion.boletas.BoletasEmitidasPort;
import pe.edu.unmsm.fisi.retail.model.CajaSesion;
import pe.edu.unmsm.fisi.retail.model.EstadoAprobacionCambio;
import pe.edu.unmsm.fisi.retail.model.EstadoCajaSesion;
import pe.edu.unmsm.fisi.retail.model.EstadoVale;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.model.SolicitudCambio;
import pe.edu.unmsm.fisi.retail.model.ValeCompra;
import pe.edu.unmsm.fisi.retail.repository.CajaSesionRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;
import pe.edu.unmsm.fisi.retail.repository.SolicitudCambioRepository;
import pe.edu.unmsm.fisi.retail.repository.ValeCompraRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Implementacion de F9.
 *
 * <p>Orden de las comprobaciones al registrar un cambio, de lo mas barato a lo mas caro y de modo que
 * el vendedor reciba primero el motivo que puede resolver: idempotencia, vendedor y turno, boleta,
 * plazo, prenda y cantidad, y por ultimo la inspeccion fisica.</p>
 */
@Service
public class CambioServiceImpl implements CambioService {

    private static final Set<String> ESTADOS_APTOS = Set.of("PAGADO", "ENTREGADO");

    private final BoletasEmitidasPort boletas;
    private final PoliticaPlazoCambio politicaPlazo;
    private final PoliticaInspeccionPrenda politicaInspeccion;
    private final PersonalTiendaRepository personalTiendaRepository;
    private final CajaSesionRepository cajaSesionRepository;
    private final SolicitudCambioRepository solicitudCambioRepository;
    private final ValeCompraRepository valeCompraRepository;
    private final int vigenciaValeDias;
    private final Clock reloj;

    @Autowired
    public CambioServiceImpl(BoletasEmitidasPort boletas,
                             PoliticaPlazoCambio politicaPlazo,
                             PoliticaInspeccionPrenda politicaInspeccion,
                             PersonalTiendaRepository personalTiendaRepository,
                             CajaSesionRepository cajaSesionRepository,
                             SolicitudCambioRepository solicitudCambioRepository,
                             ValeCompraRepository valeCompraRepository,
                             @Value("${retail.cambios.vigencia-vale-dias:30}") int vigenciaValeDias) {
        this(boletas, politicaPlazo, politicaInspeccion, personalTiendaRepository, cajaSesionRepository,
                solicitudCambioRepository, valeCompraRepository, vigenciaValeDias, Clock.systemUTC());
    }

    CambioServiceImpl(BoletasEmitidasPort boletas,
                      PoliticaPlazoCambio politicaPlazo,
                      PoliticaInspeccionPrenda politicaInspeccion,
                      PersonalTiendaRepository personalTiendaRepository,
                      CajaSesionRepository cajaSesionRepository,
                      SolicitudCambioRepository solicitudCambioRepository,
                      ValeCompraRepository valeCompraRepository,
                      int vigenciaValeDias,
                      Clock reloj) {
        this.boletas = boletas;
        this.politicaPlazo = politicaPlazo;
        this.politicaInspeccion = politicaInspeccion;
        this.personalTiendaRepository = personalTiendaRepository;
        this.cajaSesionRepository = cajaSesionRepository;
        this.solicitudCambioRepository = solicitudCambioRepository;
        this.valeCompraRepository = valeCompraRepository;
        this.vigenciaValeDias = vigenciaValeDias;
        this.reloj = reloj;
    }

    @Override
    @Transactional(readOnly = true)
    public ValidarCambioResponse validar(UUID usuarioId, String boletaNumero) {
        BoletaEmitida boleta = boletaApta(boletaNumero, vendedorActivo(usuarioId).getTiendaId(), false);
        PoliticaPlazoCambio.Evaluacion plazo = politicaPlazo.evaluar(boleta.fechaEmision());

        List<ValidarCambioResponse.ItemCambiable> items = boleta.items().stream()
                .map(item -> new ValidarCambioResponse.ItemCambiable(
                        item.sku(), item.descripcion(), item.precioPagado(), item.cantidad(),
                        disponibles(boleta.numero(), item)))
                .toList();

        return new ValidarCambioResponse(boleta.numero(), boleta.pedidoId(), boleta.fechaEmision().toString(),
                plazo.diasTranscurridos(), plazo.plazoDias(), plazo.plazoValido(), items);
    }

    @Override
    @Transactional
    public ValeCompraResponse registrar(UUID usuarioId, String idempotencyKey, RegistroCambioRequest peticion) {
        PersonalTienda vendedor = vendedorActivo(usuarioId);
        var repetida = solicitudCambioRepository.findByIdempotencyKey(idempotencyKey);
        if (repetida.isPresent()) {
            return respuestaRepetida(usuarioId, repetida.get(), peticion);
        }
        if (peticion.motivo() == pe.edu.unmsm.fisi.retail.model.MotivoCambio.FALLA_FABRICA) {
            throw new ReglaNegocioException("GARANTIA_REQUIERE_POSTVENTA",
                    "Deriva la falla de fábrica a Postventa para revisión de garantía");
        }
        CajaSesion turno = cajaSesionRepository
                .findFirstByTiendaIdAndEstadoOrderByFechaHoraAperturaDesc(vendedor.getTiendaId(), EstadoCajaSesion.ABIERTA)
                .orElseThrow(() -> new ReglaNegocioException("CAJA_NO_ABIERTA",
                        "Abre el turno de caja antes de registrar un cambio"));

        BoletaEmitida boleta = boletaApta(peticion.boletaNumero(), vendedor.getTiendaId(), true);
        repetida = solicitudCambioRepository.findByIdempotencyKey(idempotencyKey);
        if (repetida.isPresent()) {
            return respuestaRepetida(usuarioId, repetida.get(), peticion);
        }
        if (!politicaPlazo.evaluar(boleta.fechaEmision()).plazoValido()) {
            throw new ReglaNegocioException("PLAZO_VENCIDO",
                    "El plazo de cambio de la boleta " + boleta.numero() + " ya vencio");
        }

        BoletaEmitida.Item item = boleta.items().stream()
                .filter(i -> i.sku().equals(peticion.sku()))
                .findFirst()
                .orElseThrow(() -> new ReglaNegocioException("PRENDA_NO_INCLUIDA",
                        "La prenda " + peticion.sku() + " no figura en la boleta " + boleta.numero()));
        int disponibles = disponibles(boleta.numero(), item);
        if (peticion.cantidad() > disponibles) {
            throw new ReglaNegocioException("CANTIDAD_NO_DISPONIBLE",
                    "Solo quedan " + disponibles + " unidades de " + item.sku() + " por cambiar con esta boleta");
        }

        PoliticaInspeccionPrenda.Resultado inspeccion = politicaInspeccion.evaluar(peticion.motivo(), peticion.inspeccion());
        if (!inspeccion.aprobado()) {
            throw new ReglaNegocioException("INSPECCION_NO_APTA",
                    "La prenda no cumple la inspeccion: " + String.join("; ", inspeccion.observaciones()));
        }

        BigDecimal monto = item.precioPagado().multiply(BigDecimal.valueOf(peticion.cantidad()));
        OffsetDateTime ahora = OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC);
        String codigoVale = siguienteCodigoVale(ahora);

        SolicitudCambio solicitud = solicitudCambioRepository.save(SolicitudCambio.builder()
                .cajaSesionId(turno.getIdSesion())
                .ventaId(boleta.ventaId())
                .vendedorId(usuarioId)
                .boletaNumero(boleta.numero())
                .pedidoIdOrigen(boleta.pedidoId())
                .varianteSku(item.sku())
                .clienteId(peticion.clienteId())
                .cantidad(peticion.cantidad())
                .motivoCambio(peticion.motivo())
                .inspeccionEtiquetas(peticion.inspeccion().etiquetasIntactas())
                .inspeccionSinUso(peticion.inspeccion().sinSignosUso())
                .inspeccionEmpaque(peticion.inspeccion().empaqueOriginal())
                .estadoAprobacion(EstadoAprobacionCambio.APROBADO_EN_TIENDA)
                .valeTemporalCodigo(codigoVale)
                .montoAcreditado(monto)
                .idempotencyKey(idempotencyKey)
                .fechaInspeccion(ahora)
                .build());

        ValeCompra vale = valeCompraRepository.save(ValeCompra.builder()
                .codigoVale(codigoVale)
                .solicitudId(solicitud.getIdSolicitud())
                .boletaNumero(boleta.numero())
                .clienteId(peticion.clienteId())
                .tiendaId(vendedor.getTiendaId())
                .monto(monto)
                .fechaEmision(ahora)
                .fechaVencimiento(ahora.plusDays(vigenciaValeDias))
                .estado(EstadoVale.EMITIDO)
                .build());

        return respuesta(solicitud, vale, inspeccion.destino() == null ? null : inspeccion.destino().name(),
                inspeccion.observaciones());
    }

    private PersonalTienda vendedorActivo(UUID usuarioId) {
        var vendedor = personalTiendaRepository.findByUsuarioIdAndActivoTrue(usuarioId)
                .orElseThrow(() -> new AccesoDenegadoException("FORBIDDEN_USUARIO_SIN_TIENDA",
                        "El usuario autenticado no tiene un perfil activo en la tienda"));
        if (vendedor.getPerfilTienda() == pe.edu.unmsm.fisi.retail.model.PerfilTienda.CAJERO) {
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
        long cambiadas = solicitudCambioRepository.unidadesYaCambiadas(
                boletaNumero, item.sku(), EstadoAprobacionCambio.APROBADO_EN_TIENDA);
        return (int) Math.max(0, item.cantidad() - cambiadas);
    }

    /** Año de emisión en la tienda y correlativo durable. */
    private String siguienteCodigoVale(OffsetDateTime ahora) {
        int anio = ahora.atZoneSameInstant(PoliticaPlazoCambio.ZONA_TIENDA).getYear();
        String prefijo = "VALE-" + anio + "-";
        long siguiente = valeCompraRepository.siguienteCorrelativo();
        return prefijo + String.format("%05d", siguiente);
    }

    /**
     * La misma clave de idempotencia con la misma peticion devuelve el vale ya emitido; con una peticion
     * distinta es un error del cliente, no un reintento.
     */
    private ValeCompraResponse respuestaRepetida(UUID usuario, SolicitudCambio solicitud, RegistroCambioRequest peticion) {
        boolean mismaPeticion = usuario.equals(solicitud.getVendedorId())
                && solicitud.getBoletaNumero().equals(peticion.boletaNumero())
                && solicitud.getVarianteSku().equals(peticion.sku())
                && solicitud.getCantidad() == peticion.cantidad()
                && solicitud.getMotivoCambio() == peticion.motivo()
                && Objects.equals(solicitud.getClienteId(), peticion.clienteId())
                && solicitud.isInspeccionEtiquetas() == peticion.inspeccion().etiquetasIntactas()
                && solicitud.isInspeccionSinUso() == peticion.inspeccion().sinSignosUso()
                && solicitud.isInspeccionEmpaque() == peticion.inspeccion().empaqueOriginal();
        if (!mismaPeticion) {
            throw new ConflictoException("IDEMPOTENCY_CONFLICT",
                    "La clave de idempotencia ya se uso con una solicitud de cambio distinta");
        }
        ValeCompra vale = valeCompraRepository.findBySolicitudId(solicitud.getIdSolicitud())
                .orElseThrow(() -> new IllegalStateException("Cambio aprobado sin vale: " + solicitud.getIdSolicitud()));
        var inspeccion = politicaInspeccion.evaluar(solicitud.getMotivoCambio(), new InspeccionPrenda(
                solicitud.isInspeccionEtiquetas(), solicitud.isInspeccionSinUso(), solicitud.isInspeccionEmpaque()));
        return respuesta(solicitud, vale, inspeccion.destino() == null ? null : inspeccion.destino().name(),
                inspeccion.observaciones());
    }

    private ValeCompraResponse respuesta(SolicitudCambio solicitud, ValeCompra vale, String destino,
                                         List<String> observaciones) {
        Objects.requireNonNull(vale, "vale");
        return new ValeCompraResponse(
                solicitud.getIdSolicitud().toString(),
                solicitud.getEstadoAprobacion().name(),
                destino,
                solicitud.getMontoAcreditado(),
                observaciones,
                new ValeCompraResponse.Vale(
                        vale.getCodigoVale(),
                        vale.getMonto(),
                        vale.getCodigoVale(),
                        vale.getBoletaNumero(),
                        vale.getFechaEmision().toInstant().toString(),
                        vale.getFechaVencimiento().toInstant().toString(),
                        vale.getEstado().name()));
    }
}

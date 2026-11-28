package pe.edu.unmsm.fisi.retail.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pe.edu.unmsm.fisi.retail.dto.ControlDtos.CampanaVigenteResponse;
import pe.edu.unmsm.fisi.retail.dto.ControlDtos.ResumenControlResponse;
import pe.edu.unmsm.fisi.retail.dto.ControlDtos.VarianteCriticaResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.CampanasPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.CampanaComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.DisponibilidadComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.FiltroCatalogo;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.PaginaProductos;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.ProductoComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Torre de control operativa del turno (F11): RF-29, RF-30 y RF-31 (contrato v0.5, §5.11).
 *
 * <ul>
 *   <li><strong>Pickups del día (RF-30):</strong> la bandeja de Ventas (G5) de la tienda del vendedor,
 *   solo {@code LISTO_PARA_RECOJO}, del arribo más reciente al más antiguo.</li>
 *   <li><strong>Stock crítico (RF-29):</strong> sale del estado comercial de Productos (G6), que no
 *   expone cantidades (X-P0-03): {@code AGOTADO} → {@code AGOTADO}, {@code STOCK_BAJO} → {@code CRITICO}.
 *   En caché unos minutos porque arma la lista con varias llamadas.</li>
 *   <li><strong>Campañas (RF-31):</strong> las del canal {@code RETAIL} vigentes ahora, en caché 15 min.</li>
 * </ul>
 *
 * <p>El resumen nunca falla por una sola fuente caída: deja ese contador en {@code null}.</p>
 */
@Slf4j
@Service
public class ControlService {

    static final String CANAL_RETAIL = "RETAIL";
    static final String LISTO_PARA_RECOJO = "LISTO_PARA_RECOJO";
    static final String FUENTE_VENTAS = "VENTAS_G5";
    static final String FUENTE_PRODUCTOS = "PRODUCTOS_G6";
    private static final int TAMANIO_PAGINA = 100;
    private static final int PAGINAS_MAXIMAS = 20;

    private final PickupService pickupService;
    private final ProductosPort productos;
    private final CampanasPort campanas;
    private final Clock reloj;
    private final Duration ttlCampanas;
    private final Duration ttlStock;

    private volatile Cache<List<CampanaComercial>> cacheCampanas;
    private volatile Cache<List<VarianteCriticaResponse>> cacheStock;

    public ControlService(PickupService pickupService,
                          ProductosPort productos,
                          CampanasPort campanas,
                          Clock reloj,
                          @Value("${retail.control.campanas.ttl-segundos:900}") long ttlCampanasSegundos,
                          @Value("${retail.control.stock-critico.ttl-segundos:120}") long ttlStockSegundos) {
        this.pickupService = pickupService;
        this.productos = productos;
        this.campanas = campanas;
        this.reloj = reloj;
        this.ttlCampanas = Duration.ofSeconds(ttlCampanasSegundos);
        this.ttlStock = Duration.ofSeconds(ttlStockSegundos);
    }

    /** RF-30: pickups listos para recojo en la tienda del vendedor; con {@code dni}, solo los de ese titular. */
    public List<PedidoPickupResponse> pickupPendientes(UUID usuarioId, String dni) {
        return pickupService.pendientes(usuarioId).stream()
                .filter(p -> LISTO_PARA_RECOJO.equals(p.estado()))
                .filter(p -> dni == null || dni.isBlank() || dni.equals(p.clienteDni()))
                .sorted(Comparator.comparing(PedidoPickupResponse::fechaArriboTienda,
                        Comparator.nullsLast(Comparator.<String>naturalOrder())).reversed())
                .toList();
    }

    /** RF-29: variantes agotadas y críticas; primero las agotadas y, dentro de cada nivel, por nombre. */
    public List<VarianteCriticaResponse> stockCritico(UUID usuarioId) {
        pickupService.codigoSucursalDe(usuarioId); // valida el perfil de tienda (403 si no tiene)
        Cache<List<VarianteCriticaResponse>> actual = cacheStock;
        if (actual != null && actual.vigente(reloj.instant())) {
            return actual.valor();
        }
        List<VarianteCriticaResponse> lista = armarStockCritico();
        cacheStock = new Cache<>(lista, reloj.instant().plus(ttlStock));
        return lista;
    }

    /** RF-31: campañas del canal {@code RETAIL} vigentes en este momento; las vencidas no aparecen. */
    public List<CampanaVigenteResponse> campanasVigentes(UUID usuarioId) {
        pickupService.codigoSucursalDe(usuarioId);
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        return campanasPublicadas().stream()
                .filter(c -> c.vigentePara(CANAL_RETAIL, ahora))
                .sorted(Comparator.comparing(CampanaComercial::vigenteHasta,
                        Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder())))
                .map(ControlService::aRespuesta)
                .toList();
    }

    /** Contadores del badge. Una fuente caída deja su contador en {@code null} y figura en la lista. */
    public ResumenControlResponse resumen(UUID usuarioId) {
        pickupService.codigoSucursalDe(usuarioId);
        List<String> caidas = new ArrayList<>();
        Integer pickups = contar(() -> pickupPendientes(usuarioId, null).size(), FUENTE_VENTAS, caidas);
        Integer stock = contar(() -> stockCritico(usuarioId).size(), FUENTE_PRODUCTOS, caidas);
        Integer campanasActivas = contar(() -> campanasVigentes(usuarioId).size(), FUENTE_PRODUCTOS, caidas);
        return new ResumenControlResponse(pickups, stock, campanasActivas, List.copyOf(caidas), OffsetDateTime.now(reloj));
    }

    private Integer contar(Supplier<Integer> fuente, String nombre, List<String> caidas) {
        try {
            return fuente.get();
        } catch (ServicioExternoNoDisponibleException | IllegalStateException e) {
            log.warn("Torre de control: la fuente {} no respondió: {}", nombre, e.getMessage());
            if (!caidas.contains(nombre)) {
                caidas.add(nombre);
            }
            return null;
        }
    }

    private List<CampanaComercial> campanasPublicadas() {
        Cache<List<CampanaComercial>> actual = cacheCampanas;
        if (actual != null && actual.vigente(reloj.instant())) {
            return actual.valor();
        }
        List<CampanaComercial> lista = List.copyOf(campanas.listarCampanas());
        cacheCampanas = new Cache<>(lista, reloj.instant().plus(ttlCampanas));
        log.info("Campañas leídas de {}: {}", campanas.origen(), lista.size());
        return lista;
    }

    private List<VarianteCriticaResponse> armarStockCritico() {
        Map<String, Fila> filas = new HashMap<>();
        int pagina = 1;
        long vistos = 0;
        PaginaProductos actual;
        do {
            actual = productos.listarProductos(new FiltroCatalogo(null, null, null, pagina, TAMANIO_PAGINA));
            Map<String, List<VarianteComercial>> variantes = productos.variantesDe(
                    actual.items().stream().map(ProductoComercial::productId).toList());
            for (ProductoComercial producto : actual.items()) {
                for (VarianteComercial variante : variantes.getOrDefault(producto.productId(), List.of())) {
                    filas.put(variante.sku(), new Fila(producto, variante));
                }
            }
            vistos += actual.items().size();
            pagina++;
        } while (!actual.items().isEmpty() && vistos < actual.total() && pagina <= PAGINAS_MAXIMAS);

        List<String> skus = new ArrayList<>(filas.keySet());
        List<VarianteCriticaResponse> criticas = new ArrayList<>();
        for (int i = 0; i < skus.size(); i += TAMANIO_PAGINA) {
            for (DisponibilidadComercial d : productos.consultarDisponibilidad(
                    skus.subList(i, Math.min(i + TAMANIO_PAGINA, skus.size())))) {
                String nivel = nivel(d.status());
                Fila fila = filas.get(d.sku());
                if (nivel != null && fila != null) {
                    criticas.add(fila.aRespuesta(nivel));
                }
            }
        }
        criticas.sort(Comparator.comparing((VarianteCriticaResponse v) -> "AGOTADO".equals(v.nivel()) ? 0 : 1)
                .thenComparing(VarianteCriticaResponse::nombreProducto, Comparator.nullsLast(String::compareTo))
                .thenComparing(VarianteCriticaResponse::sku));
        return List.copyOf(criticas);
    }

    /** Estado comercial de G6 → nivel de F11; {@code null} si la variante no es crítica. */
    static String nivel(DisponibilidadComercial.Estado estado) {
        if (estado == null) {
            return null;
        }
        return switch (estado) {
            case AGOTADO -> "AGOTADO";
            case STOCK_BAJO -> "CRITICO";
            default -> null;
        };
    }

    private static CampanaVigenteResponse aRespuesta(CampanaComercial c) {
        return new CampanaVigenteResponse(c.campanaId(), c.titulo(), c.descripcion(), c.tipoDescuento(),
                c.valor(), c.codigoCupon(), c.montoMinimo(), c.condiciones(), c.vigenteDesde(), c.vigenteHasta());
    }

    private record Fila(ProductoComercial producto, VarianteComercial variante) {
        VarianteCriticaResponse aRespuesta(String nivel) {
            // X-P0-03: Productos no expone cantidades, así que las unidades viajan en null.
            return new VarianteCriticaResponse(variante.sku(), producto.productId(), producto.name(),
                    variante.atributo("Talla").orElse(null), variante.atributo("Color").orElse(null),
                    nivel, null, null, null);
        }
    }

    private record Cache<T>(T valor, Instant venceEn) {
        boolean vigente(Instant ahora) {
            return Objects.nonNull(valor) && ahora.isBefore(venceEn);
        }
    }
}

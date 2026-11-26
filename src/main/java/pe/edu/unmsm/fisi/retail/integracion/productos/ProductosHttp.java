package pe.edu.unmsm.fisi.retail.integracion.productos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.DisponibilidadComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.FiltroCatalogo;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.PaginaProductos;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.Precio;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Adaptador HTTP real hacia Productos y Ofertas (G6), con las rutas de su OpenAPI (contrato v0.5, §4.2).
 *
 * <ul>
 *   <li>{@code GET /productos?canal=RETAIL&pagina=&tamanio=} (paginación desde 1)</li>
 *   <li>{@code GET /productos/{id}?canal=RETAIL}, el detalle comercial con sus {@code variants} activas. Es la
 *   única vía M2M: {@code GET /productos/{id}/variantes} solo acepta tokens de usuario de G6 (403
 *   {@code SCOPE_INSUFICIENTE} con el token de servicio, verificado el 26/11)</li>
 *   <li>{@code POST /productos/codigos-barras/resolver} con {@code {codigo_barras}}, que responde solo el SKU</li>
 *   <li>{@code GET /precios/skus/{sku}?canal=RETAIL}</li>
 *   <li>{@code GET /inventario/disponibilidad/comercial?skus=a,b&canal=RETAIL} (hasta 100 SKU por llamada)</li>
 * </ul>
 *
 * <p>Cada llamada lleva el token de servicio de {@code modulo-retail} para la audiencia
 * {@code api-productos}. Una pantalla de catálogo son varias llamadas, así que las variantes y los
 * precios se guardan en caché por un tiempo corto ({@code retail.integracion.productos.cache-segundos}).
 * Un {@code 404} de precio o de código de barras es "no existe"; una caída, un timeout o un {@code 5xx}
 * se responden como {@code 503 PRODUCTOS_NO_DISPONIBLE}.</p>
 */
@Slf4j
public class ProductosHttp implements ProductosPort {

    static final String CANAL = "RETAIL";
    private static final int MAXIMO_SKUS = 100;

    private final RestClient restClient;
    private final TokenServicioG7 tokenServicio;
    private final Clock reloj;
    private final Duration ttl;
    private final Map<String, EnCache<List<VarianteComercial>>> cacheVariantes = new ConcurrentHashMap<>();
    private final Map<String, EnCache<Optional<Precio>>> cachePrecios = new ConcurrentHashMap<>();
    private final Map<String, EnCache<Map<String, String>>> cacheNombres = new ConcurrentHashMap<>();

    public ProductosHttp(RestClient restClient, TokenServicioG7 tokenServicio, Clock reloj, Duration ttl) {
        this.restClient = restClient;
        this.tokenServicio = tokenServicio;
        this.reloj = reloj;
        this.ttl = ttl;
    }

    @Override
    public PaginaProductos listarProductos(FiltroCatalogo filtro) {
        return llamar("listar el catálogo", () -> {
            PaginaProductos pagina = restClient.get()
                    .uri(uri -> {
                        uri.path("/productos").queryParam("canal", CANAL)
                                .queryParam("pagina", filtro.pagina()).queryParam("tamanio", filtro.tamanio());
                        if (filtro.q() != null && !filtro.q().isBlank()) {
                            uri.queryParam("q", filtro.q());
                        }
                        if (filtro.categoriaId() != null) {
                            uri.queryParam("categoriaId", filtro.categoriaId());
                        }
                        if (filtro.marcaId() != null) {
                            uri.queryParam("marcaId", filtro.marcaId());
                        }
                        return uri.build();
                    })
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(PaginaProductos.class);
            return pagina == null ? PaginaProductos.vacia(filtro.pagina(), filtro.tamanio()) : pagina;
        });
    }

    @Override
    public List<VarianteComercial> listarVariantes(String productoId) {
        return enCache(cacheVariantes, productoId, () -> llamar("listar variantes", () -> {
            DetalleProducto detalle;
            try {
                detalle = restClient.get()
                    .uri(uri -> uri.path("/productos/{id}").queryParam("canal", CANAL).build(productoId))
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(DetalleProducto.class);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                    return List.<VarianteComercial>of();
                }
                throw e;
            }
            return detalle == null || detalle.variants() == null ? List.<VarianteComercial>of() : List.copyOf(detalle.variants());
        }));
    }

    @Override
    public Optional<String> resolverCodigoBarras(String codigoBarras) {
        return llamar("resolver el código de barras", () -> {
            try {
                RespuestaCodigo respuesta = restClient.post()
                        .uri("/productos/codigos-barras/resolver")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("codigo_barras", codigoBarras))
                        .retrieve()
                        .body(RespuestaCodigo.class);
                return Optional.ofNullable(respuesta).map(RespuestaCodigo::sku);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()
                        || e.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
                    return Optional.<String>empty();
                }
                throw e;
            }
        });
    }

    @Override
    public Optional<Precio> consultarPrecio(String sku) {
        return enCache(cachePrecios, sku, () -> llamar("consultar el precio", () -> {
            try {
                return Optional.ofNullable(restClient.get()
                        .uri(uri -> uri.path("/precios/skus/{sku}").queryParam("canal", CANAL).build(sku))
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(Precio.class));
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                    return Optional.<Precio>empty();
                }
                throw e;
            }
        }));
    }

    @Override
    public List<DisponibilidadComercial> consultarDisponibilidad(List<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return List.of();
        }
        List<DisponibilidadComercial> resultado = new ArrayList<>();
        for (int i = 0; i < skus.size(); i += MAXIMO_SKUS) {
            List<String> tramo = skus.subList(i, Math.min(i + MAXIMO_SKUS, skus.size()));
            RespuestaDisponibilidad respuesta = llamar("consultar la disponibilidad", () -> restClient.get()
                    .uri(uri -> uri.path("/inventario/disponibilidad/comercial")
                            .queryParam("skus", String.join(",", tramo)).queryParam("canal", CANAL).build())
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(RespuestaDisponibilidad.class));
            if (respuesta != null && respuesta.items() != null) {
                resultado.addAll(respuesta.items());
            }
        }
        return resultado;
    }

    private String bearer() {
        return "Bearer " + tokenServicio.obtener();
    }

    /** Ejecuta la llamada y traduce las fallas de red y de G6 al error del contrato. */
    private <T> T llamar(String operacion, Supplier<T> llamada) {
        try {
            return llamada.get();
        } catch (RestClientResponseException e) {
            int estado = e.getStatusCode().value();
            log.warn("Productos (G6) respondió {} al {}: {}", estado, operacion, e.getResponseBodyAsString());
            if (estado == 401 || estado == 403) {
                throw new ServicioExternoNoDisponibleException("PRODUCTOS_CREDENCIAL_RECHAZADA",
                        "Productos no aceptó la credencial de servicio de Retail");
            }
            throw new ServicioExternoNoDisponibleException("PRODUCTOS_NO_DISPONIBLE",
                    "Productos no pudo atender la solicitud. Reintente en unos segundos");
        } catch (RestClientException e) {
            log.warn("Productos (G6) no respondió al {}: {}{}", operacion, e.getMessage(),
                    e.getMostSpecificCause() != e ? " | causa: " + e.getMostSpecificCause().getMessage() : "");
            throw new ServicioExternoNoDisponibleException("PRODUCTOS_NO_DISPONIBLE",
                    "Productos no respondió a tiempo. Reintente en unos segundos");
        }
    }

    @Override
    public Map<String, String> nombresDeMarcas() {
        return nombres("/marcas", "brands", "marca_id");
    }

    @Override
    public Map<String, String> nombresDeCategorias() {
        return nombres("/categorias", "categories", "categoria_id");
    }

    /** Lee {@code {<lista>: [{<id>, name}]}} de G6. Si falla, el catálogo sigue con los ids. */
    private Map<String, String> nombres(String ruta, String lista, String campoId) {
        try {
            return enCache(cacheNombres, ruta, () -> {
                com.fasterxml.jackson.databind.JsonNode cuerpo = restClient.get()
                        .uri(ruta)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(com.fasterxml.jackson.databind.JsonNode.class);
                Map<String, String> resultado = new java.util.HashMap<>();
                if (cuerpo != null && cuerpo.path(lista).isArray()) {
                    cuerpo.path(lista).forEach(n -> {
                        if (n.hasNonNull(campoId) && n.hasNonNull("name")) {
                            resultado.put(n.get(campoId).asText(), n.get("name").asText());
                        }
                    });
                }
                return Map.copyOf(resultado);
            });
        } catch (RuntimeException e) {
            log.warn("Productos (G6) no entregó {}: {}", ruta, e.getMessage());
            return Map.of();
        }
    }

    private <T> T enCache(Map<String, EnCache<T>> cache, String clave, Supplier<T> carga) {
        Instant ahora = reloj.instant();
        EnCache<T> guardado = cache.get(clave);
        if (guardado != null && ahora.isBefore(guardado.venceEn())) {
            return guardado.valor();
        }
        T valor = carga.get();
        cache.put(clave, new EnCache<>(valor, ahora.plus(ttl)));
        return valor;
    }

    private record EnCache<T>(T valor, Instant venceEn) {
    }

    /** {@code ProductoDetalleComercial} de G6; Retail solo usa sus {@code variants}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetalleProducto(@JsonProperty("variants") List<VarianteComercial> variants) {
    }

    /** {@code ResolverCodigoBarrasResponse} de G6. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RespuestaCodigo(@JsonProperty("sku") String sku) {
    }

    /** {@code ConsultaDisponibilidadComercialResponse} de G6. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RespuestaDisponibilidad(@JsonProperty("items") List<DisponibilidadComercial> items) {
    }
}

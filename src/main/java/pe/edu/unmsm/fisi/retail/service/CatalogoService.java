package pe.edu.unmsm.fisi.retail.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.edu.unmsm.fisi.retail.dto.CatalogoResponse;
import pe.edu.unmsm.fisi.retail.dto.CodigoBarrasResponse;
import pe.edu.unmsm.fisi.retail.dto.ProductoCatalogoResponse;
import pe.edu.unmsm.fisi.retail.dto.VarianteResponse;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.DisponibilidadComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.FiltroCatalogo;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.PaginaProductos;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.Precio;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.ProductoComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Catalogo y disponibilidad para el mostrador (F2: RF-04, RF-05 y RF-06).
 *
 * <p>Es una <strong>fachada de lectura</strong> sobre Productos (G6). Compone en una sola respuesta
 * lo que el modulo real reparte entre tres operaciones (catalogo, precio por SKU y disponibilidad) y
 * traduce su vocabulario de estados al del contrato de Retail.</p>
 *
 * <p><strong>Limite estricto:</strong> aqui solo se lee. Descontar stock, reservar unidades o cobrar
 * no es de este servicio: el stock lo orquesta Ventas (G4-P0-01 y G4-P0-05) y el cobro es F5.</p>
 */
@Service
@RequiredArgsConstructor
public class CatalogoService {

    /** Formato de un codigo de barras EAN-13 o Code128, seccion 5.2 del contrato. */
    private static final Pattern CODIGO_BARRAS = Pattern.compile("\\d{8,14}");

    private final ProductosPort productos;

    /** Cuarentena local de F12 (RF-33). Nula en las pruebas que solo prueban la fachada de G6. */
    private CuarentenaService cuarentena;

    @Autowired(required = false)
    void setCuarentena(CuarentenaService cuarentena) {
        this.cuarentena = cuarentena;
    }

    /** Busqueda sin tienda: ninguna variante se marca en cuarentena. */
    public CatalogoResponse buscar(String q, String categoria, String disciplina, String marca,
                                   int page, int size) {
        return buscar(q, categoria, disciplina, marca, page, size, null);
    }

    /**
     * Busqueda multicriterio del catalogo (RF-04).
     *
     * <p>Sobre la <strong>disciplina</strong>: el contrato la define como un filtro propio, pero el
     * catalogo de G6 no publica ese eje. En sus datos la disciplina <em>es</em> la categoria
     * ({@code CAT-RUNNING}, {@code CAT-FUTBOL}, {@code CAT-GYM}...), asi que se aplica sobre el mismo
     * filtro en lugar de descartarla en memoria, que ademas descuadraria el total de la pagina. Si
     * llegan las dos, manda {@code categoria}, que es la mas especifica.</p>
     *
     * <p>Cada variante lleva {@code enCuarentena = true} si la tienda del vendedor tiene una incidencia
     * activa sobre su SKU (F12, RF-33).</p>
     *
     * @param page      numero de pagina empezando en 0, como expone Retail (seccion 2.7)
     * @param usuarioId vendedor que consulta; define la tienda de la cuarentena. Puede ser nulo
     */
    public CatalogoResponse buscar(String q, String categoria, String disciplina, String marca,
                                   int page, int size, UUID usuarioId) {
        String categoriaEfectiva = primeroNoVacio(categoria, disciplina);
        Map<String, String> marcas = productos.nombresDeMarcas();
        Map<String, String> categorias = productos.nombresDeCategorias();
        FiltroCatalogo filtro = FiltroCatalogo.desdePaginacionRetail(normalizarFiltro(q),
                idDe(normalizarFiltro(categoriaEfectiva), categorias), idDe(normalizarFiltro(marca), marcas), page, size);

        PaginaProductos pagina = productos.listarProductos(filtro);
        if (pagina.items().isEmpty()) {
            return CatalogoResponse.vacia(page, size);
        }

        // Las variantes son una llamada aparte por producto (en paralelo, ver ProductosHttp); la
        // disponibilidad, una sola para toda la pagina. Resolverla por SKU multiplicaria los viajes a G6.
        Map<String, List<VarianteComercial>> variantesPorProducto = productos.variantesDe(
                pagina.items().stream().map(ProductoComercial::productId).toList());
        List<String> skus = variantesPorProducto.values().stream().flatMap(List::stream)
                .map(VarianteComercial::sku).toList();
        Map<String, VarianteResponse.Disponibilidad> estados = consultarEstados(skus);
        Set<String> bloqueados = enCuarentena(usuarioId, skus);

        // Los precios tambien van en lote: uno por SKU eran cientos de llamadas y el APIM de G6 las corta con 429.
        Map<String, Precio> precios = productos.preciosDe(skus);

        List<ProductoCatalogoResponse> items = pagina.items().stream()
                .map(producto -> armarProducto(producto, variantesPorProducto.get(producto.productId()), estados,
                        precios, bloqueados, marcas, categorias))
                .toList();

        return new CatalogoResponse(items, page, size, pagina.total());
    }

    /**
     * Resuelve el codigo que dispara el lector laser y devuelve la variante exacta (RF-04).
     *
     * <p>Son dos saltos, no uno: G6 traduce el codigo a un SKU y recien despues hay que ir por el
     * producto, su precio y su disponibilidad. El codigo de barras <strong>no es</strong> el SKU.</p>
     *
     * <p>Un codigo con un formato que ningun lector puede haber producido se responde como
     * {@code 404 CODIGO_BARRAS_NO_ENCONTRADO}, igual que uno bien formado que no existe: para el
     * vendedor son el mismo hecho, la etiqueta no corresponde a nada del catalogo, y el contrato no
     * define otro error para esta ruta.</p>
     */
    public CodigoBarrasResponse resolverCodigoBarras(String codigo) {
        return resolverCodigoBarras(codigo, null);
    }

    /**
     * Igual que {@link #resolverCodigoBarras(String)}, marcando {@code enCuarentena} segun la tienda del
     * vendedor: con {@code true}, la SPA bloquea la adicion al carrito (RF-33).
     */
    public CodigoBarrasResponse resolverCodigoBarras(String codigo, UUID usuarioId) {
        String leido = codigo == null ? "" : codigo.trim();
        String sku = CODIGO_BARRAS.matcher(leido).matches()
                ? productos.resolverCodigoBarras(leido).orElseThrow(() -> noEncontrado(leido))
                : null;
        if (sku == null) {
            throw noEncontrado(leido);
        }

        ProductoComercial producto = buscarProductoDelSku(sku).orElseThrow(() -> noEncontrado(leido));
        VarianteComercial variante = productos.listarVariantes(producto.productId()).stream()
                .filter(v -> sku.equals(v.sku()))
                .findFirst()
                .orElseThrow(() -> noEncontrado(leido));

        VarianteResponse respuesta = armarVariante(variante, consultarEstados(List.of(sku)),
                productos.preciosDe(List.of(sku)), enCuarentena(usuarioId, List.of(sku)));
        return new CodigoBarrasResponse(leido, producto.productId(), producto.name(),
                respuesta.precio(), respuesta);
    }

    /**
     * Nombre legible de la prenda de un SKU, con su talla y color si los tiene, por ejemplo
     * {@code Camiseta Peru (M / Blanco)}. Lo usa F12 para la bandeja de cuarentena (RF-33).
     *
     * @return vacio si el SKU no figura en el catalogo
     */
    public Optional<String> nombreDelSku(String sku) {
        return buscarProductoDelSku(sku).map(producto -> {
            String detalle = productos.listarVariantes(producto.productId()).stream()
                    .filter(variante -> sku.equals(variante.sku()))
                    .findFirst()
                    .map(variante -> java.util.stream.Stream.of(variante.atributo("Talla"), variante.atributo("Color"))
                            .flatMap(Optional::stream)
                            .collect(java.util.stream.Collectors.joining(" / ")))
                    .orElse("");
            return detalle.isEmpty() ? producto.name() : producto.name() + " (" + detalle + ")";
        });
    }

    /**
     * Encuentra a que producto pertenece un SKU.
     *
     * <p>G6 no publica una busqueda inversa de SKU a producto, asi que se recorre el catalogo. Con
     * el mock de la Semana 8 (18 productos) es inmediato; cuando el modulo real este conectado, este
     * es el punto exacto donde hara falta pedirle esa operacion o cachear el mapa.</p>
     */
    private Optional<ProductoComercial> buscarProductoDelSku(String sku) {
        PaginaProductos todos = productos.listarProductos(FiltroCatalogo.primeraPagina(100));
        return todos.items().stream()
                .filter(producto -> productos.listarVariantes(producto.productId()).stream()
                        .anyMatch(variante -> sku.equals(variante.sku())))
                .findFirst();
    }

    private ProductoCatalogoResponse armarProducto(ProductoComercial producto,
                                                   List<VarianteComercial> variantes,
                                                   Map<String, VarianteResponse.Disponibilidad> estados,
                                                   Map<String, Precio> precios,
                                                   Set<String> enCuarentena,
                                                   Map<String, String> marcas, Map<String, String> categorias) {
        String categoria = nombreDe(producto.categoriaId(), categorias);
        List<VarianteResponse> armadas = variantes.stream()
                .map(variante -> armarVariante(variante, estados, precios, enCuarentena))
                .toList();
        return new ProductoCatalogoResponse(
                producto.productId(),
                producto.name(),
                nombreDe(producto.marcaId(), marcas),
                categoria,
                categoria,
                ProductoCatalogoResponse.precioDesde(armadas),
                producto.images().isEmpty() ? null : producto.images().get(0),
                armadas);
    }

    /**
     * Arma la variante que ve el mostrador: talla, color, precio y semaforo.
     *
     * <p>{@code stockTienda} viaja en <strong>nulo</strong>: Productos no expone cantidades a los
     * canales (X-P0-03), y el contrato ya advierte que la SPA no debe depender de ese numero. El
     * semaforo se decide con el estado, no con unidades inventadas.</p>
     *
     * <p>{@code enCuarentena} sale de la cuarentena local de F12 (RF-33): el SKU tiene una incidencia
     * activa ({@code PENDIENTE_REPORTE} o {@code EN_CUARENTENA}) en la tienda del vendedor.</p>
     */
    private VarianteResponse armarVariante(VarianteComercial variante,
                                           Map<String, VarianteResponse.Disponibilidad> estados,
                                           Map<String, Precio> precios,
                                           Set<String> enCuarentena) {
        Precio vigente = precios.get(variante.sku());
        BigDecimal precio = vigente == null ? null : vigente.precioEfectivo();
        return new VarianteResponse(
                variante.sku(),
                variante.atributo("Talla").orElse(null),
                variante.atributo("Color").orElse(null),
                precio,
                estados.getOrDefault(variante.sku(), VarianteResponse.Disponibilidad.DISPONIBLE),
                null,
                enCuarentena.contains(variante.sku()));
    }

    /** SKU en cuarentena activa en la tienda del vendedor; vacio sin vendedor o sin F12 disponible. */
    private Set<String> enCuarentena(UUID usuarioId, List<String> skus) {
        return cuarentena == null ? Set.of() : cuarentena.skusBloqueados(usuarioId, skus);
    }

    /** Pide la disponibilidad de todos los SKUs de una vez y la deja traducida por SKU. */
    private Map<String, VarianteResponse.Disponibilidad> consultarEstados(List<String> skus) {
        Map<String, VarianteResponse.Disponibilidad> estados = new LinkedHashMap<>();
        for (DisponibilidadComercial disponibilidad : productos.consultarDisponibilidad(skus)) {
            estados.put(disponibilidad.sku(), traducir(disponibilidad.status()));
        }
        return estados;
    }

    /**
     * Traduce el vocabulario de G6 al del contrato de Retail (RF-06).
     *
     * <p>{@code INDETERMINADO} se muestra como disponible y sin {@code stockTienda}: el propio
     * contrato de G6 aclara que <strong>nunca equivale a agotado</strong>, y tratarlo como tal
     * perderia ventas de articulos que si estan en la percha. El vendedor confirma con el cliente
     * antes de prometer la entrega. {@code SOLO_ALMACEN_CENTRAL} no aparece en esta traduccion
     * porque G6 no distingue hoy el almacen central; queda pendiente con Maylle y Mihael.</p>
     */
    private VarianteResponse.Disponibilidad traducir(DisponibilidadComercial.Estado estado) {
        if (estado == null) {
            return VarianteResponse.Disponibilidad.DISPONIBLE;
        }
        return switch (estado) {
            case DISPONIBLE, INDETERMINADO -> VarianteResponse.Disponibilidad.DISPONIBLE;
            case STOCK_BAJO -> VarianteResponse.Disponibilidad.ULTIMAS_UNIDADES;
            case AGOTADO -> VarianteResponse.Disponibilidad.AGOTADO_TOTAL;
        };
    }

    private RecursoNoEncontradoException noEncontrado(String codigo) {
        return new RecursoNoEncontradoException("CODIGO_BARRAS_NO_ENCONTRADO",
                "El codigo de barras " + codigo + " no corresponde a ningun articulo del catalogo");
    }

    private String primeroNoVacio(String preferido, String alternativo) {
        return (preferido == null || preferido.isBlank()) ? alternativo : preferido;
    }

    /** Un filtro en blanco es un filtro ausente: el buscador vacio no debe filtrar por cadena vacia. */
    private String normalizarFiltro(String valor) {
        return (valor == null || valor.isBlank()) ? null : valor.trim();
    }

    /** Nombre publicado por G6 para ese id; si no lo hay, el id tal cual. */
    private static String nombreDe(String id, Map<String, String> nombres) {
        return id == null ? null : nombres.getOrDefault(id, id);
    }

    /**
     * El POS filtra por lo que muestra (el nombre), pero G6 filtra por id: traduce el nombre a su id
     * sin distinguir mayúsculas. Un valor que ya es id, o que no figura, se envía tal cual.
     */
    private static String idDe(String valor, Map<String, String> nombres) {
        if (valor == null) {
            return null;
        }
        return nombres.entrySet().stream()
                .filter(e -> e.getValue().equalsIgnoreCase(valor))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(valor);
    }
}

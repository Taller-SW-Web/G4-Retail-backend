package pe.edu.unmsm.fisi.retail.integracion.productos.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.DisponibilidadComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.FiltroCatalogo;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.PaginaProductos;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.Precio;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.ProductoComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mock del modulo Productos (G6) para la Semana 8.
 *
 * <p>Responde con la misma firma que el modulo real, leyendo los datos de
 * {@code resources/mocks/catalogo-g6.json}: 18 productos deportivos con 40 SKUs, cada uno con su
 * codigo de barras EAN-13 unico. Gracias a eso, la pantalla de mostrador puede demostrarse de punta
 * a punta sin depender de que G6 este levantado, y el dia que lo este, cambiar de uno a otro es
 * solo cambiar de perfil (seccion 6 del contrato v0.4).</p>
 *
 * <p>Se activa con el perfil {@code mock}.</p>
 */
@Slf4j
@Service
@Profile("mock")
public class ProductosMock implements ProductosPort {

    private static final String RUTA_SEMILLA = "mocks/catalogo-g6.json";

    private final List<CatalogoSemilla.Producto> productos;
    private final Map<String, String> skuPorCodigoBarras;
    private final Map<String, CatalogoSemilla.Variante> variantePorSku;

    public ProductosMock(ObjectMapper mapper) {
        this.productos = cargarSemilla(mapper);
        this.skuPorCodigoBarras = productos.stream()
                .flatMap(p -> p.variantes().stream())
                .collect(Collectors.toMap(CatalogoSemilla.Variante::codigoBarras,
                        CatalogoSemilla.Variante::sku, (a, b) -> a, LinkedHashMap::new));
        this.variantePorSku = productos.stream()
                .flatMap(p -> p.variantes().stream())
                .collect(Collectors.toMap(CatalogoSemilla.Variante::sku,
                        Function.identity(), (a, b) -> a, LinkedHashMap::new));
        log.info("Mock de Productos (G6) activo: {} productos y {} SKUs", productos.size(), variantePorSku.size());
    }

    @Override
    public PaginaProductos listarProductos(FiltroCatalogo filtro) {
        List<ProductoComercial> coincidencias = productos.stream()
                .filter(p -> coincideTexto(p, filtro.q()))
                .filter(p -> filtro.categoriaId() == null || filtro.categoriaId().equals(p.categoriaId()))
                .filter(p -> filtro.marcaId() == null || filtro.marcaId().equals(p.marcaId()))
                .map(this::aProductoComercial)
                .toList();

        int desde = (filtro.pagina() - 1) * filtro.tamanio();
        if (desde >= coincidencias.size()) {
            return new PaginaProductos(List.of(), filtro.pagina(), filtro.tamanio(), coincidencias.size());
        }
        int hasta = Math.min(desde + filtro.tamanio(), coincidencias.size());
        return new PaginaProductos(coincidencias.subList(desde, hasta),
                filtro.pagina(), filtro.tamanio(), coincidencias.size());
    }

    @Override
    public List<VarianteComercial> listarVariantes(String productoId) {
        return productos.stream()
                .filter(p -> p.productId().equals(productoId))
                .findFirst()
                .map(p -> p.variantes().stream().map(this::aVarianteComercial).toList())
                .orElseGet(List::of);
    }

    @Override
    public Optional<String> resolverCodigoBarras(String codigoBarras) {
        return Optional.ofNullable(codigoBarras).map(skuPorCodigoBarras::get);
    }

    @Override
    public Optional<Precio> consultarPrecio(String sku) {
        return Optional.ofNullable(variantePorSku.get(sku))
                .map(v -> new Precio(v.sku(), v.precioRegular(), v.precioOferta(), "PEN"));
    }

    @Override
    public List<DisponibilidadComercial> consultarDisponibilidad(List<String> skus) {
        if (skus == null) {
            return List.of();
        }
        return skus.stream()
                .map(sku -> {
                    CatalogoSemilla.Variante v = variantePorSku.get(sku);
                    // Un SKU que el mock no conoce no es un SKU agotado: es uno del que no hay dato.
                    DisponibilidadComercial.Estado estado = v == null
                            ? DisponibilidadComercial.Estado.INDETERMINADO
                            : DisponibilidadComercial.Estado.valueOf(v.disponibilidad());
                    return new DisponibilidadComercial(sku, estado);
                })
                .toList();
    }

    private boolean coincideTexto(CatalogoSemilla.Producto producto, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }
        return normalizar(producto.name()).contains(normalizar(q));
    }

    /** Minusculas y sin tildes, para que "futbol" encuentre "fútbol", como hace la SPA. */
    private String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase();
    }

    private ProductoComercial aProductoComercial(CatalogoSemilla.Producto p) {
        return new ProductoComercial(p.productId(), p.slug(), p.name(), p.description(),
                p.categoriaId(), p.tipoProductoId(), p.marcaId(), p.tieneVariantes(),
                p.skuBase(), p.images());
    }

    private VarianteComercial aVarianteComercial(CatalogoSemilla.Variante v) {
        return new VarianteComercial(v.variantId(), v.sku(),
                v.identifyingAttributes(), v.nonIdentifyingAttributes(), v.images());
    }

    private static List<CatalogoSemilla.Producto> cargarSemilla(ObjectMapper mapper) {
        try (InputStream entrada = new ClassPathResource(RUTA_SEMILLA).getInputStream()) {
            return mapper.readValue(entrada, CatalogoSemilla.class).productos();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la semilla del mock en " + RUTA_SEMILLA, e);
        }
    }
}

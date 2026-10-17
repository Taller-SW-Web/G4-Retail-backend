package pe.edu.unmsm.fisi.retail.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unmsm.fisi.retail.dto.CatalogoResponse;
import pe.edu.unmsm.fisi.retail.dto.CodigoBarrasResponse;
import pe.edu.unmsm.fisi.retail.service.CatalogoService;

/**
 * Catalogo de prendas y lectura por codigo de barras (F2: RF-04, RF-05 y RF-06).
 *
 * <p>Las dos rutas que necesita el mostrador para encontrar un articulo: la busqueda que se escribe
 * y el disparo del lector laser. Ambas son de <strong>solo lectura</strong>; ni descuentan stock ni
 * cobran, eso es F5 y lo orquesta Ventas.</p>
 *
 * <p>La tienda no viaja como parametro: sale del perfil del vendedor autenticado (seccion 5.2 del
 * contrato v0.4), para que nadie pueda consultar el inventario de una tienda que no es la suya.</p>
 */
@RestController
@RequestMapping("/api/v1/retail/catalogo")
@RequiredArgsConstructor
public class CatalogoController {

    /** Tamanio de pagina por defecto del contrato (seccion 2.7). */
    private static final String TAMANIO_POR_DEFECTO = "20";

    private final CatalogoService catalogoService;

    /**
     * Busqueda multicriterio del catalogo.
     *
     * <p>La ruta responde tambien en {@code /buscar} porque asi la consume hoy la SPA; es el mismo
     * recurso con dos nombres, no dos busquedas distintas.</p>
     *
     * @param page pagina empezando en 0, como expone Retail
     */
    @GetMapping({"", "/buscar"})
    public CatalogoResponse buscar(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String disciplina,
            @RequestParam(required = false) String marca,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = TAMANIO_POR_DEFECTO) int size) {
        return catalogoService.buscar(q, categoria, disciplina, marca, page, size);
    }

    /**
     * Resuelve la etiqueta que acaba de leer el lector y devuelve la variante exacta.
     *
     * <p>Es la ruta critica del mostrador: entra por aqui todo lo que el vendedor escanea con la
     * prenda en la mano. Responde {@code 404 CODIGO_BARRAS_NO_ENCONTRADO} si la etiqueta no
     * corresponde a ningun articulo del catalogo.</p>
     */
    @GetMapping("/codigo-barras/{codigo}")
    public CodigoBarrasResponse resolverCodigoBarras(@PathVariable String codigo) {
        return catalogoService.resolverCodigoBarras(codigo);
    }
}

package pe.edu.unmsm.fisi.retail.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pe.edu.unmsm.fisi.retail.dto.CatalogoResponse;

/**
 * Mantiene caliente la caché del catálogo de G6 (contrato §5.2, rendimiento).
 *
 * <p>El POS pide el catálogo completo al iniciar sesión ({@code GET /catalogo?page=0&size=100}). Sin
 * caché eso son decenas de llamadas a G6 y el primer vendedor del día esperaría. Este job recorre el
 * catálogo con la misma paginación al arrancar y luego cada pocos minutos, así la caché de
 * {@code ProductosHttp} siempre tiene datos y el vendedor no espera a G6.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "retail.catalogo.precalentado.habilitado", havingValue = "true", matchIfMissing = true)
public class PrecalentadoCatalogoJob {

    /** Mismo tamaño de página que usa el POS, para que la caché sirva exactamente esas consultas. */
    static final int TAMANIO_PAGINA = 100;
    private static final int PAGINAS_MAXIMAS = 20;

    private final CatalogoService catalogoService;

    @Scheduled(initialDelayString = "${retail.catalogo.precalentado.retraso-inicial-ms:10000}",
            fixedDelayString = "${retail.catalogo.precalentado.intervalo-ms:240000}")
    public void precalentar() {
        long inicio = System.currentTimeMillis();
        try {
            int productos = 0;
            for (int pagina = 0; pagina < PAGINAS_MAXIMAS; pagina++) {
                CatalogoResponse respuesta = catalogoService.buscar(null, null, null, null, pagina, TAMANIO_PAGINA);
                productos += respuesta.items().size();
                if (respuesta.items().isEmpty() || productos >= respuesta.total()) {
                    break;
                }
            }
            log.info("Catálogo precalentado: {} productos en {} ms", productos, System.currentTimeMillis() - inicio);
        } catch (RuntimeException e) {
            log.warn("No se pudo precalentar el catálogo: {}", e.getMessage());
        }
    }
}

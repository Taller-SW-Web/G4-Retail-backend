package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.dto.CatalogoResponse;
import pe.edu.unmsm.fisi.retail.dto.ProductoCatalogoResponse;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Contrato §5.2: el catálogo se precalienta con la misma paginación que usa el POS. */
@DisplayName("PrecalentadoCatalogoJob — caché del catálogo siempre caliente")
class PrecalentadoCatalogoJobTest {

    private final CatalogoService catalogo = mock(CatalogoService.class);
    private final PrecalentadoCatalogoJob job = new PrecalentadoCatalogoJob(catalogo);

    private static CatalogoResponse pagina(int page, int items, long total) {
        List<ProductoCatalogoResponse> lista = Collections.nCopies(items, mock(ProductoCatalogoResponse.class));
        return new CatalogoResponse(lista, page, PrecalentadoCatalogoJob.TAMANIO_PAGINA, total);
    }

    @Test
    @DisplayName("Recorre todas las páginas de 100 hasta cubrir el total")
    void recorreTodasLasPaginas() {
        when(catalogo.buscar(isNull(), isNull(), isNull(), isNull(), eq(0), eq(100))).thenReturn(pagina(0, 100, 152));
        when(catalogo.buscar(isNull(), isNull(), isNull(), isNull(), eq(1), eq(100))).thenReturn(pagina(1, 52, 152));

        job.precalentar();

        verify(catalogo).buscar(null, null, null, null, 0, 100);
        verify(catalogo).buscar(null, null, null, null, 1, 100);
        verify(catalogo, never()).buscar(isNull(), isNull(), isNull(), isNull(), eq(2), anyInt());
    }

    @Test
    @DisplayName("Si G6 no responde, lo registra y no tumba el job")
    void g6CaidoNoRompe() {
        when(catalogo.buscar(isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenThrow(new ServicioExternoNoDisponibleException("PRODUCTOS_NO_DISPONIBLE", "caído"));

        assertThatCode(job::precalentar).doesNotThrowAnyException();
    }
}

package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El job reenvia a Ventas los cambios PENDIENTE cada 5 minutos (F9, contrato 5.9). */
@DisplayName("CambioPedidosPendientesJob — reintento de pedidos de cambio")
class CambioPedidosPendientesJobTest {

    private final CambioService cambios = mock(CambioService.class);
    private final CambioPedidosPendientesJob job = new CambioPedidosPendientesJob(cambios);

    @Test
    @DisplayName("Reintenta los pendientes a traves del servicio")
    void reintenta() {
        when(cambios.reintentarPedidosPendientes()).thenReturn(2);

        job.reintentar();

        verify(cambios).reintentarPedidosPendientes();
    }

    @Test
    @DisplayName("Sin pendientes confirmados tampoco falla")
    void sinConfirmados() {
        when(cambios.reintentarPedidosPendientes()).thenReturn(0);

        assertThatCode(job::reintentar).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Un error del reintento no tumba el scheduler: el siguiente ciclo vuelve a intentar")
    void errorNoSePropaga() {
        when(cambios.reintentarPedidosPendientes()).thenThrow(new IllegalStateException("BD caida"));

        assertThatCode(job::reintentar).doesNotThrowAnyException();
    }
}

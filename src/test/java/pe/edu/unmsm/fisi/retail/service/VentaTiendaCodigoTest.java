package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Criterio del acuerdo R5 con Ventas (G5) y de la decisión D-05 del contrato: en la venta de
 * mostrador, {@code envio.tiendaId} viaja siempre como código de sucursal ({@code TIENDA-...}),
 * aunque el perfil del vendedor todavía guarde la tienda como UUID.
 */
@DisplayName("F5 — tiendaId enviado a Ventas (R5, D-05)")
class VentaTiendaCodigoTest {

    private static final String UUID_MIRAFLORES = "11111111-1111-1111-1111-111111111111";

    private final CodigosSucursal codigos = new CodigosSucursal(UUID_MIRAFLORES + "=TIENDA-MIRAFLORES");

    private VentaRequest venta() {
        return new VentaRequest(
                "POS-01", "turno-1", "f3e541f2-a82c-4107-9a09-41dae6992253",
                List.of(new VentaRequest.ItemVentaDto("SKU-001", 1, new BigDecimal("249.00"))),
                new VentaRequest.PagoDto("EFECTIVO", new BigDecimal("250.00"), null, null),
                new VentaRequest.ComprobanteDto("BOLETA", "72458912", "Cliente"),
                false, null, null);
    }

    @Test
    @DisplayName("Un perfil con la tienda como UUID envía a Ventas su código TIENDA-...")
    void perfilConUuidEnviaCodigoDeSucursal() {
        VentaService servicio = new VentaService(null, codigos);

        VentasCrearPedidoRequest pedido = servicio.armarPedidoVentasRequest(
                venta(), new BigDecimal("249.00"), OffsetDateTime.now(), UUID_MIRAFLORES);

        assertThat(pedido.envio().tiendaId()).isEqualTo("TIENDA-MIRAFLORES");
    }

    @Test
    @DisplayName("Un perfil que ya guarda el código lo envía sin cambios")
    void perfilConCodigoLoEnviaIgual() {
        VentaService servicio = new VentaService(null, codigos);

        VentasCrearPedidoRequest pedido = servicio.armarPedidoVentasRequest(
                venta(), new BigDecimal("249.00"), OffsetDateTime.now(), "TIENDA-SAN-ISIDRO");

        assertThat(pedido.envio().tiendaId()).isEqualTo("TIENDA-SAN-ISIDRO");
    }

    @Test
    @DisplayName("Un UUID sin código configurado no se adivina: se envía tal cual")
    void uuidSinCodigoNoSeAdivina() {
        assertThat(codigos.codigoDe("22222222-2222-2222-2222-222222222222"))
                .isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(codigos.codigoDe(UUID_MIRAFLORES.toUpperCase())).isEqualTo("TIENDA-MIRAFLORES");
        assertThat(codigos.codigoDe(null)).isNull();
    }
}

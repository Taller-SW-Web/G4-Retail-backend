package pe.edu.unmsm.fisi.retail.integracion.ventas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RegistroPedidosMostrador — pedidos de mostrador por documento")
class RegistroPedidosMostradorTest {

    @Test
    @DisplayName("Guarda cada pedido una sola vez por documento")
    void registraSinDuplicar() {
        RegistroPedidosMostrador registro = new RegistroPedidosMostrador();
        registro.registrar("72345678", "PED-2026-00412");
        registro.registrar("72345678", "PED-2026-00412");
        registro.registrar("72345678", "PED-2026-00413");
        registro.registrar("45678912", "PED-2026-00414");

        assertThat(registro.pedidosDe("72345678")).containsExactlyInAnyOrder("PED-2026-00412", "PED-2026-00413");
        assertThat(registro.pedidosDe("45678912")).containsExactly("PED-2026-00414");
    }

    @Test
    @DisplayName("Ignora ventas sin documento del comprador")
    void ignoraDocumentoVacio() {
        RegistroPedidosMostrador registro = new RegistroPedidosMostrador();
        registro.registrar(null, "PED-2026-00412");
        registro.registrar(" ", "PED-2026-00412");
        registro.registrar("72345678", null);

        assertThat(registro.pedidosDe("72345678")).isEmpty();
        assertThat(registro.pedidosDe(null)).isEmpty();
    }
}

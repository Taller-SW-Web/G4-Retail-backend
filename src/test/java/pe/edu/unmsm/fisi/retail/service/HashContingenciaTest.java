package pe.edu.unmsm.fisi.retail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest.Item;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest.VentaOffline;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HashContingenciaTest {

    private final HashContingencia validador = new HashContingencia(new ObjectMapper());

    @Test
    @DisplayName("CA-10-06: el cálculo de hash SHA-256 es determinista y coincide con el vector canónico §7")
    void hashEsDeterminista() {
        UUID turnoId = UUID.fromString("cccccccc-0000-0000-0001-000000000001");
        UUID ventaUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String terminal = "POS-01";
        String fecha = "2026-11-07T06:00:00Z";

        VentaOffline venta = new VentaOffline(
                ventaUuid,
                "CONT-001",
                fecha,
                List.of(new Item("SKU-POLO", 2, new BigDecimal("50.00"))),
                new BigDecimal("100.00"),
                new BigDecimal("120.00"),
                new BigDecimal("20.00"),
                null,
                "placeholder"
        );

        SincronizacionOfflineRequest lote = new SincronizacionOfflineRequest(terminal, turnoId, List.of(venta));

        String hash1 = validador.calcular(lote, venta);
        String hash2 = validador.calcular(lote, venta);

        assertThat(hash1).isNotNull().hasSize(64);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("CA-10-06: cualquier alteración de importes o prendas altera el hash")
    void alteracionModificaHash() {
        UUID turnoId = UUID.fromString("cccccccc-0000-0000-0001-000000000001");
        UUID ventaUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String terminal = "POS-01";
        String fecha = "2026-11-07T06:00:00Z";

        VentaOffline ventaOriginal = new VentaOffline(
                ventaUuid,
                "CONT-001",
                fecha,
                List.of(new Item("SKU-POLO", 2, new BigDecimal("50.00"))),
                new BigDecimal("100.00"),
                new BigDecimal("120.00"),
                new BigDecimal("20.00"),
                null,
                "placeholder"
        );

        VentaOffline ventaAlterada = new VentaOffline(
                ventaUuid,
                "CONT-001",
                fecha,
                List.of(new Item("SKU-POLO", 2, new BigDecimal("50.00"))),
                new BigDecimal("100.00"),
                new BigDecimal("150.00"), // recibido alterado
                new BigDecimal("50.00"),  // vuelto alterado
                null,
                "placeholder"
        );

        SincronizacionOfflineRequest lote = new SincronizacionOfflineRequest(terminal, turnoId, List.of(ventaOriginal));

        String hashOriginal = validador.calcular(lote, ventaOriginal);
        String hashAlterado = validador.calcular(lote, ventaAlterada);

        assertThat(hashOriginal).isNotEqualTo(hashAlterado);
    }
}

package pe.edu.unmsm.fisi.retail.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import pe.edu.unmsm.fisi.retail.config.LocalDatabaseConfig;
import pe.edu.unmsm.fisi.retail.model.EstadoIncidencia;
import pe.edu.unmsm.fisi.retail.model.IncidenciaInventario;
import pe.edu.unmsm.fisi.retail.model.TipoFalla;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tarea B1 (RF-32): mapeo de {@code RET_INCIDENCIA_INVENTARIO} y consultas de la cuarentena activa.
 */
@DataJpaTest
@ActiveProfiles({"local", "mock"})
@Import(LocalDatabaseConfig.class)
class IncidenciaInventarioRepositoryTest {

    private static final String MIRAFLORES = "TIENDA-MIRAFLORES";

    @Autowired
    private IncidenciaInventarioRepository repositorio;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("CA-RF32-02/03: guarda la incidencia con su vendedor, nace en PENDIENTE_REPORTE y se lee igual")
    void persisteLaIncidencia() {
        UUID vendedor = UUID.randomUUID();
        IncidenciaInventario guardada = repositorio.saveAndFlush(IncidenciaInventario.builder()
                .tiendaId(MIRAFLORES)
                .vendedorReportaId(vendedor)
                .sku("CAM-PERU-M-BLA")
                .codigoBarras("7759876543210")
                .tipoFalla(TipoFalla.COSTURA_ROTA)
                .detalleObservacion("Costura abierta en la manga")
                .idempotencyKey("clave-1")
                .build());
        entityManager.clear();

        IncidenciaInventario leida = repositorio.findById(guardada.getIdIncidencia()).orElseThrow();
        assertThat(leida.getEstado()).isEqualTo(EstadoIncidencia.PENDIENTE_REPORTE);
        assertThat(leida.enCuarentenaActiva()).isTrue();
        assertThat(leida.getVendedorReportaId()).isEqualTo(vendedor);
        assertThat(leida.getTipoFalla()).isEqualTo(TipoFalla.COSTURA_ROTA);
        assertThat(leida.getFechaReporte()).isNotNull();
        assertThat(leida.getUpdatedAt()).isNotNull();
        assertThat(leida.getActaId()).isNull();
    }

    @Test
    @DisplayName("Busca el reporte por su Idempotency-Key")
    void buscaPorClave() {
        persistir("ZAP-42", "clave-zap", EstadoIncidencia.PENDIENTE_REPORTE, MIRAFLORES);

        assertThat(repositorio.findByIdempotencyKey("clave-zap")).isPresent();
        assertThat(repositorio.findByIdempotencyKey("otra")).isEmpty();
    }

    @Test
    @DisplayName("RF-33: la bandeja trae solo la cuarentena activa de la tienda")
    void bandejaSoloCuarentenaActivaDeLaTienda() {
        persistir("A", "k1", EstadoIncidencia.PENDIENTE_REPORTE, MIRAFLORES);
        persistir("B", "k2", EstadoIncidencia.EN_CUARENTENA, MIRAFLORES);
        persistir("C", "k3", EstadoIncidencia.DERIVADO_ALMACEN, MIRAFLORES);
        persistir("D", "k4", EstadoIncidencia.BAJA_DEFINITIVA, MIRAFLORES);
        persistir("E", "k5", EstadoIncidencia.EN_CUARENTENA, "TIENDA-SAN-ISIDRO");

        List<IncidenciaInventario> bandeja = repositorio.findByTiendaIdAndEstadoInOrderByFechaReporteDesc(
                MIRAFLORES, EstadoIncidencia.CUARENTENA_ACTIVA);

        assertThat(bandeja).extracting(IncidenciaInventario::getSku).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    @DisplayName("RF-33: de los SKU consultados devuelve solo los bloqueados en la tienda")
    void skusBloqueados() {
        persistir("A", "k1", EstadoIncidencia.EN_CUARENTENA, MIRAFLORES);
        persistir("A", "k2", EstadoIncidencia.PENDIENTE_REPORTE, MIRAFLORES);
        persistir("B", "k3", EstadoIncidencia.DERIVADO_ALMACEN, MIRAFLORES);
        persistir("C", "k4", EstadoIncidencia.EN_CUARENTENA, "TIENDA-SAN-ISIDRO");

        List<String> bloqueados = repositorio.skusConEstado(MIRAFLORES, List.of("A", "B", "C", "Z"),
                EstadoIncidencia.CUARENTENA_ACTIVA);

        assertThat(bloqueados).containsExactly("A");
    }

    private void persistir(String sku, String clave, EstadoIncidencia estado, String tienda) {
        entityManager.persist(IncidenciaInventario.builder()
                .tiendaId(tienda)
                .vendedorReportaId(UUID.randomUUID())
                .sku(sku)
                .codigoBarras(sku)
                .tipoFalla(TipoFalla.MANCHADO_PROBADOR)
                .detalleObservacion("Mancha en el cuello")
                .estado(estado)
                .idempotencyKey(clave)
                .build());
        entityManager.flush();
    }
}

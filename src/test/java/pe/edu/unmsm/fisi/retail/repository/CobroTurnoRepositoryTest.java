package pe.edu.unmsm.fisi.retail.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import pe.edu.unmsm.fisi.retail.config.LocalDatabaseConfig;
import pe.edu.unmsm.fisi.retail.model.CobroTurno;
import pe.edu.unmsm.fisi.retail.model.EstadoCobroTurno;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mapeo y consulta de {@code RET_COBRO_TURNO}: la suma de efectivo que lee el arqueo y la unicidad
 * de la llave de idempotencia.
 */
@DataJpaTest
@ActiveProfiles({"local", "mock"})
@Import(LocalDatabaseConfig.class)
class CobroTurnoRepositoryTest {

    @Autowired
    private CobroTurnoRepository repositorio;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("Suma solo el efectivo del turno y deja fuera tarjeta y anulados")
    void sumaSoloEfectivoVigente() {
        UUID turno = UUID.randomUUID();
        entityManager.persist(cobro(turno, "k1", "EFECTIVO", "100.00", EstadoCobroTurno.CONFIRMADO));
        entityManager.persist(cobro(turno, "k2", "EFECTIVO", "50.50", EstadoCobroTurno.PENDIENTE_CONFIRMACION));
        entityManager.persist(cobro(turno, "k3", "EFECTIVO", "20.00", EstadoCobroTurno.EN_REVISION_SUPERVISOR));
        entityManager.persist(cobro(turno, "k4", "EFECTIVO", "999.00", EstadoCobroTurno.ANULADO));
        entityManager.persist(cobro(turno, "k5", "TARJETA_POS", "300.00", EstadoCobroTurno.CONFIRMADO));
        entityManager.persist(cobro(UUID.randomUUID(), "k6", "EFECTIVO", "777.00", EstadoCobroTurno.CONFIRMADO));
        entityManager.flush();

        BigDecimal total = repositorio.totalEfectivoVigente(turno, EstadoCobroTurno.ANULADO);

        assertThat(total).isEqualByComparingTo("170.50");
    }

    @Test
    @DisplayName("Un turno sin cobros devuelve null (el servicio lo traduce a cero)")
    void turnoSinCobrosDevuelveNull() {
        assertThat(repositorio.totalEfectivoVigente(UUID.randomUUID(), EstadoCobroTurno.ANULADO)).isNull();
    }

    @Test
    @DisplayName("Busca el cobro por su llave de idempotencia")
    void buscaPorLlave() {
        entityManager.persistAndFlush(cobro(UUID.randomUUID(), "llave-x", "EFECTIVO", "10.00", EstadoCobroTurno.CONFIRMADO));

        assertThat(repositorio.findByIdempotencyKey("llave-x")).isPresent();
        assertThat(repositorio.findByIdempotencyKey("otra")).isEmpty();
    }

    @Test
    @DisplayName("La llave de idempotencia es unica: un segundo cobro con la misma llave falla")
    void llaveDuplicadaFalla() {
        entityManager.persistAndFlush(cobro(UUID.randomUUID(), "repetida", "EFECTIVO", "10.00", EstadoCobroTurno.CONFIRMADO));

        assertThatThrownBy(() -> entityManager.persistAndFlush(
                cobro(UUID.randomUUID(), "repetida", "EFECTIVO", "10.00", EstadoCobroTurno.CONFIRMADO)))
                .isInstanceOfAny(DataIntegrityViolationException.class, jakarta.persistence.PersistenceException.class);
    }

    private CobroTurno cobro(UUID turno, String llave, String medio, String total, EstadoCobroTurno estado) {
        return CobroTurno.builder()
                .cajaSesionId(turno)
                .idempotencyKey(llave)
                .medioPago(medio)
                .montoTotal(new BigDecimal(total))
                .montoRecibido(new BigDecimal(total))
                .vuelto(new BigDecimal("0.00"))
                .estado(estado)
                .build();
    }
}

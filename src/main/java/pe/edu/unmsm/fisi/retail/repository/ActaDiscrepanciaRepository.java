package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pe.edu.unmsm.fisi.retail.model.ActaDiscrepancia;

import java.util.UUID;

/** Acceso a {@code RET_ACTA_DISCREPANCIA}. */
public interface ActaDiscrepanciaRepository extends JpaRepository<ActaDiscrepancia, UUID> {

    /** Siguiente correlativo del acta, de la secuencia que publico la DBA. */
    @Query(value = "SELECT nextval('ret_acta_merma_seq')", nativeQuery = true)
    long siguienteCorrelativo();
}

package pe.edu.unmsm.fisi.retail.repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import pe.edu.unmsm.fisi.retail.model.ContingenciaLog;
import java.util.*;
public interface ContingenciaLogRepository extends JpaRepository<ContingenciaLog,UUID> {
    Optional<ContingenciaLog> findByVentaLocalUuid(String uuid);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ContingenciaLog c where c.ventaLocalUuid=:uuid")
    Optional<ContingenciaLog> bloquear(@Param("uuid") String uuid);
}

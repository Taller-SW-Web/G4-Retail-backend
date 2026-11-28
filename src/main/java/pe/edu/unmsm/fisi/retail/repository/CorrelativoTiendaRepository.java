package pe.edu.unmsm.fisi.retail.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import pe.edu.unmsm.fisi.retail.model.CorrelativoTienda;

import java.util.Optional;

/** Acceso a {@code RET_CORRELATIVO}. */
public interface CorrelativoTiendaRepository extends JpaRepository<CorrelativoTienda, CorrelativoTienda.Clave> {

    /** Bloquea la fila: dos cambios a la vez en la misma tienda no toman el mismo numero. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CorrelativoTienda> findByTiendaIdAndAnioAndTipo(String tiendaId, int anio, String tipo);
}

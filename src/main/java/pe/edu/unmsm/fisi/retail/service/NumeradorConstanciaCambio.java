package pe.edu.unmsm.fisi.retail.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.model.CorrelativoTienda;
import pe.edu.unmsm.fisi.retail.repository.CorrelativoTiendaRepository;

import java.time.Instant;
import java.util.Locale;

/**
 * Numero de la constancia de cambio: {@code CAMB-2026-000123}, correlativo por año y tienda
 * (F9, contrato 5.9). Se asigna en los dos tipos de cambio.
 *
 * <p>Bloquea la fila del correlativo, asi que dos cambios simultaneos en la misma tienda no toman el
 * mismo numero. Corre dentro de la transaccion del cambio: si el cambio falla, el numero no se pierde.</p>
 */
@Component
@RequiredArgsConstructor
public class NumeradorConstanciaCambio {

    static final String TIPO = "CAMBIO";

    private final CorrelativoTiendaRepository correlativos;

    @Transactional(propagation = Propagation.MANDATORY)
    public String siguiente(String tiendaId, Instant momento) {
        int anio = momento.atZone(PoliticaPlazoCambio.ZONA_TIENDA).getYear();
        CorrelativoTienda fila = correlativos.findByTiendaIdAndAnioAndTipo(tiendaId, anio, TIPO)
                .orElseGet(() -> new CorrelativoTienda(tiendaId, anio, TIPO, 0));
        fila.setUltimo(fila.getUltimo() + 1);
        correlativos.save(fila);
        return String.format(Locale.ROOT, "CAMB-%d-%06d", anio, fila.getUltimo());
    }
}

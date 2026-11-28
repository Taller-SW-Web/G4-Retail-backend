package pe.edu.unmsm.fisi.retail.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import pe.edu.unmsm.fisi.retail.integracion.cambios.SkusEnCuarentenaPort;
import pe.edu.unmsm.fisi.retail.model.EstadoIncidencia;
import pe.edu.unmsm.fisi.retail.repository.IncidenciaInventarioRepository;

/**
 * Cuarentena de F12 para el cambio directo de F9 (contrato 5.9 y 5.12): una prenda nueva no puede
 * entregarse si su SKU tiene una incidencia activa ({@code PENDIENTE_REPORTE} o {@code EN_CUARENTENA})
 * en la tienda. Es la misma regla que aplica {@link CuarentenaService} al carrito.
 *
 * <p>El cambio trae la tienda del perfil (UUID); las incidencias guardan el codigo de sucursal
 * ({@code TIENDA-...}), por eso se traduce con {@link CodigosSucursal}.</p>
 */
@Component
public class SkusEnCuarentenaF12 implements SkusEnCuarentenaPort {

    private final IncidenciaInventarioRepository incidencias;
    private final CodigosSucursal codigosSucursal;

    public SkusEnCuarentenaF12(IncidenciaInventarioRepository incidencias, CodigosSucursal codigosSucursal) {
        this.incidencias = incidencias;
        this.codigosSucursal = codigosSucursal;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean enCuarentena(String tiendaId, String sku) {
        if (tiendaId == null || sku == null) {
            return false;
        }
        String tienda = codigosSucursal.codigoDe(tiendaId);
        return !incidencias.skusConEstado(tienda, List.of(sku), EstadoIncidencia.CUARENTENA_ACTIVA).isEmpty();
    }
}

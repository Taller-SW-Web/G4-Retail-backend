package pe.edu.unmsm.fisi.retail.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.dto.ActaEmitidaResponse;
import pe.edu.unmsm.fisi.retail.dto.EmisionActaRequest;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.ParametroInvalidoException;
import pe.edu.unmsm.fisi.retail.model.ActaDiscrepancia;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.ActaDiscrepanciaRepository;
import pe.edu.unmsm.fisi.retail.repository.IncidenciasActaRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.UUID;

/**
 * Acta de discrepancia o merma de tienda (F12, RF-34, contrato 5.12).
 *
 * <ul>
 *   <li>Solo la emite un SUPERVISOR, sobre incidencias de su tienda.</li>
 *   <li>Solo entran incidencias en {@code EN_CUARENTENA}: las que Inventory ya acepto. Si alguna no lo
 *       esta, el acta no se emite y nada cambia ({@code 409 INCIDENCIA_NO_EN_CUARENTENA}).</li>
 *   <li>Cada incidencia queda en el estado que define el destino y guarda el acta que la despacho.</li>
 *   <li>El acta no llama a G6 ni escribe en el outbox: la notificacion sale por el outbox de incidencias.</li>
 * </ul>
 */
@Service
public class ActaDiscrepanciaService {

    private static final String EN_CUARENTENA = "EN_CUARENTENA";

    private final PersonalTiendaRepository personal;
    private final ActaDiscrepanciaRepository actas;
    private final IncidenciasActaRepository incidencias;
    private final CodigosSucursal codigosSucursal;
    private final GeneradorNumeroActa numerador;
    private final Clock reloj;

    public ActaDiscrepanciaService(PersonalTiendaRepository personal, ActaDiscrepanciaRepository actas,
                                   IncidenciasActaRepository incidencias, CodigosSucursal codigosSucursal,
                                   Clock reloj) {
        this.personal = personal;
        this.actas = actas;
        this.incidencias = incidencias;
        this.codigosSucursal = codigosSucursal;
        this.reloj = reloj;
        this.numerador = new GeneradorNumeroActa(reloj, actas::siguienteCorrelativo);
    }

    @Transactional
    public ActaEmitidaResponse emitir(UUID usuarioId, EmisionActaRequest peticion) {
        PersonalTienda supervisor = supervisorActivo(usuarioId);
        // LinkedHashSet mutable tambien sin el campo: Set.of() no admite remove y daba 500 en vez de LISTA_VACIA.
        Set<UUID> ids = new LinkedHashSet<>();
        if (peticion.incidenciasIds() != null) {
            ids.addAll(peticion.incidenciasIds());
        }
        ids.remove(null);
        if (ids.isEmpty()) {
            throw new ParametroInvalidoException("LISTA_VACIA", "incidenciasIds",
                    "Selecciona al menos una prenda en cuarentena para emitir el acta");
        }

        // La incidencia guarda el codigo de sucursal (TIENDA-...), no el UUID del perfil.
        String tienda = codigosSucursal.codigoDe(supervisor.getTiendaId());

        // En el orden en que el supervisor las eligio: asi salen en el acta impresa.
        Map<UUID, IncidenciasActaRepository.Incidencia> porId = incidencias.bloquear(ids, tienda)
                .stream().collect(Collectors.toMap(IncidenciasActaRepository.Incidencia::id, Function.identity()));
        List<IncidenciasActaRepository.Incidencia> encontradas = ids.stream()
                .map(porId::get).filter(Objects::nonNull).toList();
        boolean todasEnCuarentena = encontradas.size() == ids.size()
                && encontradas.stream().allMatch(i -> EN_CUARENTENA.equals(i.estado()));
        if (!todasEnCuarentena) {
            throw new ConflictoException("INCIDENCIA_NO_EN_CUARENTENA",
                    "Alguna prenda no esta en cuarentena en esta tienda: ya fue liquidada, Inventory aun no la"
                            + " confirma o es de otra tienda. El acta no se emitio");
        }

        String estado = peticion.tipoDestino().estadoIncidencia();
        OffsetDateTime ahora = OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC);
        String observaciones = peticion.observaciones() == null || peticion.observaciones().isBlank()
                ? null : peticion.observaciones().trim();
        ActaDiscrepancia acta = actas.save(ActaDiscrepancia.builder()
                .numeroActa(numerador.siguiente())
                .tiendaId(tienda)
                .supervisorId(usuarioId)
                .tipoDestino(peticion.tipoDestino())
                .observaciones(observaciones)
                .totalPrendas(encontradas.size())
                .fechaEmision(ahora)
                .build());
        actas.flush();
        incidencias.liquidar(ids, acta.getIdActa(), estado);

        return new ActaEmitidaResponse(acta.getNumeroActa(), ahora.toInstant().toString(), acta.getTiendaId(),
                acta.getTipoDestino().name(), observaciones,
                new ActaEmitidaResponse.Supervisor(usuarioId.toString(), supervisor.getCodigoVendedor()),
                acta.getTotalPrendas(),
                encontradas.stream().map(i -> new ActaEmitidaResponse.Prenda(i.id().toString(), i.sku(),
                        i.tipoFalla(), i.detalleObservacion(), estado)).toList());
    }

    private PersonalTienda supervisorActivo(UUID usuarioId) {
        PersonalTienda perfil = personal.findByUsuarioIdAndActivoTrue(usuarioId)
                .orElseThrow(() -> new AccesoDenegadoException("FORBIDDEN_USUARIO_SIN_TIENDA",
                        "El usuario autenticado no tiene un perfil activo en la tienda"));
        if (perfil.getPerfilTienda() != PerfilTienda.SUPERVISOR) {
            throw new AccesoDenegadoException("ROL_NO_PERMITIDO", "Solo un supervisor puede emitir actas de merma");
        }
        return perfil;
    }
}

package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.edu.unmsm.fisi.retail.dto.IncidenciaResponse;
import pe.edu.unmsm.fisi.retail.dto.RegistroIncidenciaRequest;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.ParametroInvalidoException;
import pe.edu.unmsm.fisi.retail.integracion.inventario.IncidenciaReportadaPayload;
import pe.edu.unmsm.fisi.retail.model.CajaSesion;
import pe.edu.unmsm.fisi.retail.model.EstadoCajaSesion;
import pe.edu.unmsm.fisi.retail.model.EstadoIncidencia;
import pe.edu.unmsm.fisi.retail.model.IncidenciaInventario;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.model.TipoFalla;
import pe.edu.unmsm.fisi.retail.repository.CajaSesionRepository;
import pe.edu.unmsm.fisi.retail.repository.IncidenciaInventarioRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;
import pe.edu.unmsm.fisi.retail.service.outbox.OutboxPublicador;
import pe.edu.unmsm.fisi.retail.service.outbox.TipoEventoOutbox;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tarea B2 (RF-32): registro de la incidencia y su notificación por el outbox (contrato §5.12).
 * Las pruebas parten de los criterios de aceptación de la spec de F12.
 */
@DisplayName("IncidenciaInventarioService — reporte de incidencias (RF-32)")
class IncidenciaInventarioServiceTest {

    private static final UUID VENDEDOR = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final String TIENDA = "TIENDA-MIRAFLORES";

    private IncidenciaInventarioRepository incidencias;
    private PersonalTiendaRepository personal;
    private CajaSesionRepository turnos;
    private CatalogoService catalogo;
    private OutboxPublicador outbox;
    private IncidenciaInventarioService servicio;

    @BeforeEach
    void preparar() {
        incidencias = mock(IncidenciaInventarioRepository.class);
        personal = mock(PersonalTiendaRepository.class);
        turnos = mock(CajaSesionRepository.class);
        catalogo = mock(CatalogoService.class);
        outbox = mock(OutboxPublicador.class);
        Clock reloj = Clock.fixed(Instant.parse("2026-11-26T15:30:00Z"), ZoneOffset.UTC);
        servicio = new IncidenciaInventarioService(incidencias, personal, turnos, new CodigosSucursal(""),
                catalogo, outbox, reloj);

        when(personal.findByUsuarioIdAndActivoTrue(VENDEDOR)).thenReturn(Optional.of(perfil(PerfilTienda.VENDEDOR)));
        when(turnos.findFirstByTiendaIdAndEstadoOrderByFechaHoraAperturaDesc(TIENDA, EstadoCajaSesion.ABIERTA))
                .thenReturn(Optional.of(new CajaSesion()));
        when(incidencias.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(incidencias.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        when(catalogo.nombreDelSku("CAM-PERU-M-BLA")).thenReturn(Optional.of("Camiseta Perú (M / Blanco)"));
    }

    @Test
    @DisplayName("CA-RF32-02/03: guarda la incidencia en PENDIENTE_REPORTE con el vendedor autenticado")
    void registraLaIncidencia() {
        IncidenciaResponse respuesta = servicio.registrar(VENDEDOR, "clave-1", peticion(TipoFalla.COSTURA_ROTA, "Costura dañada en la manga"));

        ArgumentCaptor<IncidenciaInventario> guardada = ArgumentCaptor.forClass(IncidenciaInventario.class);
        verify(incidencias).save(guardada.capture());
        IncidenciaInventario incidencia = guardada.getValue();
        assertThat(incidencia.getEstado()).isEqualTo(EstadoIncidencia.PENDIENTE_REPORTE);
        assertThat(incidencia.getVendedorReportaId()).isEqualTo(VENDEDOR);
        assertThat(incidencia.getTiendaId()).isEqualTo(TIENDA);
        assertThat(incidencia.getTipoFalla()).isEqualTo(TipoFalla.COSTURA_ROTA);
        assertThat(incidencia.getNombreProducto()).isEqualTo("Camiseta Perú (M / Blanco)");
        assertThat(incidencia.getIdempotencyKey()).isEqualTo("clave-1");

        assertThat(respuesta.estado()).isEqualTo(EstadoIncidencia.PENDIENTE_REPORTE);
        assertThat(respuesta.sku()).isEqualTo("CAM-PERU-M-BLA");
        assertThat(respuesta.incidenciaId()).isEqualTo(incidencia.getIdIncidencia().toString());
    }

    @Test
    @DisplayName("§5.12: deja el evento para Inventory en el outbox con el incidenciaId como clave")
    void dejaElEventoEnElOutbox() {
        IncidenciaResponse respuesta = servicio.registrar(VENDEDOR, "clave-1", peticion(TipoFalla.EXTRAVIO_NO_UBICADO, "No está en el perchero"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).registrar(eq(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA), eq(respuesta.incidenciaId()),
                payload.capture());
        IncidenciaReportadaPayload cuerpo = (IncidenciaReportadaPayload) payload.getValue();
        assertThat(cuerpo.externalIncidentId()).isEqualTo(respuesta.incidenciaId());
        assertThat(cuerpo.type()).isEqualTo("NO_UBICADA");
        assertThat(cuerpo.locationId()).isEqualTo(TIENDA);
        assertThat(cuerpo.quantity()).isEqualTo(1);
        assertThat(cuerpo.actorRef()).isEqualTo(VENDEDOR.toString());
    }

    @Test
    @DisplayName("Sin código de barras (búsqueda por SKU) guarda el SKU como código leído")
    void sinCodigoDeBarrasUsaElSku() {
        servicio.registrar(VENDEDOR, "clave-1", new RegistroIncidenciaRequest("CAM-PERU-M-BLA", " ",
                TipoFalla.MANCHADO_PROBADOR, "Mancha de maquillaje", null));

        ArgumentCaptor<IncidenciaInventario> guardada = ArgumentCaptor.forClass(IncidenciaInventario.class);
        verify(incidencias).save(guardada.capture());
        assertThat(guardada.getValue().getCodigoBarras()).isEqualTo("CAM-PERU-M-BLA");
    }

    @Test
    @DisplayName("RF-32: una observación de menos de 5 caracteres (sin contar espacios) se rechaza")
    void rechazaObservacionCorta() {
        assertThatThrownBy(() -> servicio.registrar(VENDEDOR, "clave-1", peticion(TipoFalla.COSTURA_ROTA, "  mal  ")))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasMessageContaining("5 caracteres");
        verify(incidencias, never()).save(any());
        verify(outbox, never()).registrar(any(), any(), any());
    }

    @Test
    @DisplayName("§5.12: sin turno de caja abierto responde 409 TURNO_NO_ABIERTO")
    void exigeTurnoAbierto() {
        when(turnos.findFirstByTiendaIdAndEstadoOrderByFechaHoraAperturaDesc(TIENDA, EstadoCajaSesion.ABIERTA))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.registrar(VENDEDOR, "clave-1", peticion(TipoFalla.COSTURA_ROTA, "Costura dañada")))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("TURNO_NO_ABIERTO");
        verify(incidencias, never()).save(any());
    }

    @Test
    @DisplayName("Un usuario sin perfil activo en la tienda no puede reportar")
    void exigePerfilActivo() {
        when(personal.findByUsuarioIdAndActivoTrue(VENDEDOR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.registrar(VENDEDOR, "clave-1", peticion(TipoFalla.COSTURA_ROTA, "Costura dañada")))
                .isInstanceOf(AccesoDenegadoException.class);
    }

    @Test
    @DisplayName("🔁 El mismo reporte reenviado devuelve la incidencia original sin duplicarla")
    void reintentoDevuelveLaMisma() {
        IncidenciaInventario original = IncidenciaInventario.builder()
                .idIncidencia(UUID.randomUUID()).sku("CAM-PERU-M-BLA").tipoFalla(TipoFalla.COSTURA_ROTA)
                .detalleObservacion("Costura dañada").estado(EstadoIncidencia.EN_CUARENTENA).build();
        when(incidencias.findByIdempotencyKey("clave-1")).thenReturn(Optional.of(original));

        IncidenciaResponse respuesta = servicio.registrar(VENDEDOR, "clave-1", peticion(TipoFalla.COSTURA_ROTA, "Costura dañada"));

        assertThat(respuesta.incidenciaId()).isEqualTo(original.getIdIncidencia().toString());
        assertThat(respuesta.estado()).isEqualTo(EstadoIncidencia.EN_CUARENTENA);
        verify(incidencias, never()).save(any());
        verify(outbox, never()).registrar(any(), any(), any());
    }

    @Test
    @DisplayName("🔁 La misma clave con otro cuerpo responde 409 IDEMPOTENCY_CONFLICT")
    void mismaClaveOtroCuerpo() {
        IncidenciaInventario original = IncidenciaInventario.builder()
                .idIncidencia(UUID.randomUUID()).sku("OTRO-SKU").tipoFalla(TipoFalla.COSTURA_ROTA)
                .detalleObservacion("Costura dañada").build();
        when(incidencias.findByIdempotencyKey("clave-1")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> servicio.registrar(VENDEDOR, "clave-1", peticion(TipoFalla.COSTURA_ROTA, "Costura dañada")))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("Si Productos no responde, la incidencia se registra igual, sin nombre")
    void productosCaidoNoBloquea() {
        when(catalogo.nombreDelSku(any())).thenThrow(new RuntimeException("G6 caído"));

        servicio.registrar(VENDEDOR, "clave-1", peticion(TipoFalla.COSTURA_ROTA, "Costura dañada"));

        ArgumentCaptor<IncidenciaInventario> guardada = ArgumentCaptor.forClass(IncidenciaInventario.class);
        verify(incidencias).save(guardada.capture());
        assertThat(guardada.getValue().getNombreProducto()).isNull();
    }

    private static RegistroIncidenciaRequest peticion(TipoFalla tipo, String observacion) {
        return new RegistroIncidenciaRequest("CAM-PERU-M-BLA", "7759876543210", tipo, observacion, null);
    }

    private static PersonalTienda perfil(PerfilTienda perfilTienda) {
        PersonalTienda perfil = new PersonalTienda();
        perfil.setUsuarioId(VENDEDOR);
        perfil.setTiendaId(TIENDA);
        perfil.setPerfilTienda(perfilTienda);
        perfil.setActivo(true);
        return perfil;
    }
}

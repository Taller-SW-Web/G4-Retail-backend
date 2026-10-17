package pe.edu.unmsm.fisi.retail.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.util.UUID;

/**
 * Inicializador idempotente de personal operativo de tienda.
 *
 * <p>Asegura que las cuentas requeridas para la operación e integración (como el vendedor asignado
 * por Seguridad G7) queden registradas en {@code RET_PERSONAL_TIENDA} en el arranque de la aplicación,
 * tanto en ambientes locales como en despliegues con Supabase donde no se ejecutan scripts SQL
 * manuales. Si el registro ya existe, no realiza ninguna acción.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalTiendaInitializer implements ApplicationRunner {

    private final PersonalTiendaRepository personalTiendaRepository;

    private static final UUID USUARIO_G7 = UUID.fromString("b9e222a5-cf86-4c95-9611-891886160821");
    private static final UUID USUARIO_VENDEDOR_SEMILLA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID USUARIO_SUPERVISOR_SEMILLA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000004");
    private static final UUID TIENDA_DEFAULT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        inicializarPersonalSiNoExiste(USUARIO_G7, "VEN-002", PerfilTienda.VENDEDOR);
        inicializarPersonalSiNoExiste(USUARIO_VENDEDOR_SEMILLA, "VEN-001", PerfilTienda.VENDEDOR);
        inicializarPersonalSiNoExiste(USUARIO_SUPERVISOR_SEMILLA, "SUP-001", PerfilTienda.SUPERVISOR);
    }

    private void inicializarPersonalSiNoExiste(UUID usuarioId, String codigoVendedor, PerfilTienda perfilTienda) {
        try {
            if (!personalTiendaRepository.existsByUsuarioId(usuarioId)) {
                PersonalTienda personal = PersonalTienda.builder()
                        .usuarioId(usuarioId)
                        .tiendaId(TIENDA_DEFAULT)
                        .codigoVendedor(codigoVendedor)
                        .perfilTienda(perfilTienda)
                        .activo(true)
                        .build();
                personalTiendaRepository.save(personal);
                log.info("Personal inicializado en RET_PERSONAL_TIENDA: codigo={}, usuarioId={}, perfil={}",
                        codigoVendedor, usuarioId, perfilTienda);
            }
        } catch (Exception e) {
            log.warn("No se pudo auto-inicializar personal {} ({}): {}", codigoVendedor, usuarioId, e.getMessage());
        }
    }
}

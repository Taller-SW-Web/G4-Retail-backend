package pe.edu.unmsm.fisi.retail.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.dto.PerfilResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.util.UUID;

/**
 * Autenticacion y perfil del personal de mostrador (F1).
 *
 * <p>Retail no es duenio de las identidades: el usuario vive en Seguridad (G7) y aqui solo se
 * resuelve <strong>que puede hacer esa persona en esta tienda</strong>, leyendo
 * {@code RET_PERSONAL_TIENDA}. Por eso el perfil no viaja en el token: se consulta.</p>
 *
 * <p>Limite de la funcionalidad: esta clase no abre ni cierra turnos de caja y no toca montos de
 * dinero; eso es F8.</p>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final PersonalTiendaRepository personalTiendaRepository;

    /**
     * Perfil operativo del usuario autenticado.
     *
     * @param usuarioId el {@code sub} del token, que identifica a la persona en Seguridad
     * @throws AccesoDenegadoException si no tiene un perfil activo en la tienda
     */
    @Transactional(readOnly = true)
    public PerfilResponse obtenerPerfil(UUID usuarioId) {
        PersonalTienda personal = personalTiendaRepository.findByUsuarioIdAndActivoTrue(usuarioId)
                .orElseThrow(() -> new AccesoDenegadoException(
                        "FORBIDDEN_USUARIO_SIN_TIENDA",
                        "El usuario autenticado no tiene un perfil activo en la tienda"));

        return PerfilResponse.desde(personal);
    }
}

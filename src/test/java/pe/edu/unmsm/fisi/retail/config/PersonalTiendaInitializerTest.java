package pe.edu.unmsm.fisi.retail.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalTiendaInitializerTest {

    @Mock
    private PersonalTiendaRepository repository;

    @InjectMocks
    private PersonalTiendaInitializer initializer;

    private static final UUID USUARIO_G7 = UUID.fromString("b9e222a5-cf86-4c95-9611-891886160821");

    @Test
    @DisplayName("Cuando no existe el vendedor G7, lo inserta en la base de datos")
    void insertaPersonalSiNoExiste() {
        when(repository.existsByUsuarioId(any())).thenReturn(false);

        initializer.run(new DefaultApplicationArguments());

        // Se deben registrar los 3 usuarios base (G7, vendedor semilla y supervisor semilla)
        verify(repository, times(3)).save(any(PersonalTienda.class));
    }

    @Test
    @DisplayName("Cuando el personal ya existe en la base de datos, no realiza ninguna inserción (idempotente)")
    void noInsertaSiYaExiste() {
        when(repository.existsByUsuarioId(any())).thenReturn(true);

        initializer.run(new DefaultApplicationArguments());

        verify(repository, never()).save(any(PersonalTienda.class));
    }
}

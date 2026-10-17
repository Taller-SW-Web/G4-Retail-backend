package pe.edu.unmsm.fisi.retail.service;

import org.springframework.stereotype.Service;
import pe.edu.unmsm.fisi.retail.dto.ClienteAltaRapidaRequest;
import pe.edu.unmsm.fisi.retail.dto.ClienteResponse;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementacion del padron de clientes de mostrador (F3).
 *
 * <p>La funcionalidad administra identidades, no un historial de ventas, por eso el padron vive en
 * memoria mientras no exista la tabla de clientes: el alta rapida guarda al cliente nuevo y la
 * busqueda lo encuentra durante la misma sesion del backend. Cambiar este almacen por un repositorio
 * JPA no altera el contrato de la API.</p>
 *
 * <p>Limite de la funcionalidad: no procesa ventas, precios, carritos de compra ni cobros de
 * dinero.</p>
 */
@Service
public class ClienteServiceImpl implements ClienteService {

    /** Padron indexado por numero de documento. Concurrente porque la API atiende peticiones en paralelo. */
    private final Map<String, ClienteResponse> padron = new ConcurrentHashMap<>();

    /** Clientes de ejemplo para poder demostrar la busqueda antes de registrar uno nuevo. */
    public ClienteServiceImpl() {
        darDeAlta("DNI", "12345678", "Ana", "Quispe");
        darDeAlta("DNI", "87654321", "Luis", "Ramirez");
    }

    @Override
    public ClienteResponse buscarPorDocumento(String tipoDocumento, String numeroDocumento) {
        ClienteResponse cliente = padron.get(numeroDocumento);
        if (cliente == null) {
            throw new RecursoNoEncontradoException("CLIENTE_NO_ENCONTRADO",
                    "El documento " + numeroDocumento + " no figura en el padron de la tienda");
        }
        return cliente;
    }

    @Override
    public ClienteResponse registrarAltaRapida(ClienteAltaRapidaRequest request) {
        if (padron.containsKey(request.numeroDocumento())) {
            throw new ConflictoException("CLIENTE_YA_REGISTRADO",
                    "El documento " + request.numeroDocumento() + " ya esta registrado en el padron");
        }
        return darDeAlta(request.tipoDocumento(), request.numeroDocumento(),
                request.nombres(), request.apellidos());
    }

    /** Da de alta un cliente con identificador nuevo y lo deja disponible para la busqueda. */
    private ClienteResponse darDeAlta(String tipoDocumento, String numeroDocumento,
                                      String nombres, String apellidos) {
        String nombreCompleto = (nombres + " " + apellidos).trim();
        ClienteResponse cliente = new ClienteResponse(
                UUID.randomUUID().toString(), tipoDocumento, numeroDocumento, nombreCompleto);
        padron.put(numeroDocumento, cliente);
        return cliente;
    }
}

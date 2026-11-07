package pe.edu.unmsm.fisi.retail.service;

import org.springframework.stereotype.Service;
import pe.edu.unmsm.fisi.retail.dto.ClienteAltaRapidaRequest;
import pe.edu.unmsm.fisi.retail.dto.ClienteResponse;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.service.ValidadorDocumento.DocumentoValidado;

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
 * <p>Toda entrada pasa primero por {@link ValidadorDocumento} (RF-09): un documento con formato
 * invalido se rechaza con {@code 400 DATO_INVALIDO} antes de consultar o modificar el padron.</p>
 *
 * <p>Limite de la funcionalidad: no procesa ventas, precios, carritos de compra ni cobros de
 * dinero.</p>
 */
@Service
public class ClienteServiceImpl implements ClienteService {

    /**
     * Padron indexado por {@code TIPO:NUMERO}. El tipo forma parte de la clave porque un pasaporte
     * puede coincidir en digitos con un DNI y son personas distintas. Concurrente porque la API
     * atiende peticiones en paralelo.
     */
    private final Map<String, ClienteResponse> padron = new ConcurrentHashMap<>();

    /** Clientes de ejemplo para poder demostrar la busqueda antes de registrar uno nuevo. */
    public ClienteServiceImpl() {
        darDeAlta(ValidadorDocumento.validar("DNI", "12345678"), "Ana", "Quispe");
        darDeAlta(ValidadorDocumento.validar("DNI", "87654321"), "Luis", "Ramirez");
        darDeAlta(ValidadorDocumento.validar("CE", "001234567"), "Carla", "Mendoza");
    }

    @Override
    public ClienteResponse buscarPorDocumento(String tipoDocumento, String numeroDocumento) {
        DocumentoValidado documento = ValidadorDocumento.validarParaBusqueda(tipoDocumento, numeroDocumento);
        ClienteResponse cliente = padron.get(clave(documento));
        if (cliente == null) {
            throw new RecursoNoEncontradoException("CLIENTE_NO_ENCONTRADO",
                    "El " + documento.tipo() + " " + documento.numero()
                            + " no figura en el padron de la tienda");
        }
        return cliente;
    }

    @Override
    public ClienteResponse registrarAltaRapida(ClienteAltaRapidaRequest request) {
        DocumentoValidado documento =
                ValidadorDocumento.validar(request.tipoDocumento(), request.numeroDocumento());
        return darDeAlta(documento, request.nombres(), request.apellidos());
    }

    /** Da de alta un cliente con identificador nuevo y lo deja disponible para la busqueda. */
    private ClienteResponse darDeAlta(DocumentoValidado documento, String nombres, String apellidos) {
        String nombreCompleto = (nombres + " " + apellidos).trim();
        ClienteResponse cliente = new ClienteResponse(UUID.randomUUID().toString(),
                documento.tipo().name(), documento.numero(), nombreCompleto);
        // putIfAbsent: si dos vendedores registran el mismo documento a la vez, gana el primero.
        ClienteResponse previo = padron.putIfAbsent(clave(documento), cliente);
        if (previo != null) {
            throw new ConflictoException("CLIENTE_YA_REGISTRADO",
                    "El " + documento.tipo() + " " + documento.numero()
                            + " ya esta registrado en el padron");
        }
        return cliente;
    }

    private static String clave(DocumentoValidado documento) {
        return documento.tipo().name() + ":" + documento.numero();
    }
}

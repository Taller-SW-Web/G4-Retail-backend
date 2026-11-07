package pe.edu.unmsm.fisi.retail.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pe.edu.unmsm.fisi.retail.dto.DetallePedidoHistoricoResponse;
import pe.edu.unmsm.fisi.retail.dto.DetallePedidoHistoricoResponse.Cliente;
import pe.edu.unmsm.fisi.retail.dto.DetallePedidoHistoricoResponse.EventoEstado;
import pe.edu.unmsm.fisi.retail.dto.DetallePedidoHistoricoResponse.Linea;
import pe.edu.unmsm.fisi.retail.dto.PaginaPedidosResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoResumenDto;
import pe.edu.unmsm.fisi.retail.exception.ParametroInvalidoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.integracion.ventas.PedidoVentas;
import pe.edu.unmsm.fisi.retail.integracion.ventas.PedidosVentasPort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Consulta y seguimiento histórico de pedidos (F6): RF-16 y RF-17.
 *
 * <p>Los pedidos y su historial son de Ventas (G5); aquí solo se filtran, se ordenan y se pasan a la
 * hora de Lima. Por código se consulta el pedido directo en Ventas; por DNI/RUC, los pedidos que el
 * puerto encuentra para ese documento.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistorialPedidosService {

    static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final Pattern DOCUMENTO = Pattern.compile("^(\\d{8}|\\d{11})$");
    private static final int TAMANO_MAXIMO = 100;

    private final PedidosVentasPort ventas;

    /**
     * RF-16: pedidos por código o por documento, del más reciente al más antiguo.
     *
     * @throws ParametroInvalidoException   sin filtros, con documento mal formado o con paginación fuera de rango
     * @throws RecursoNoEncontradoException {@code PEDIDO_NO_ENCONTRADO} si no hay ningún pedido
     */
    public PaginaPedidosResponse buscar(String codigoPedido, String documentoCliente, int page, int size) {
        String codigo = limpiar(codigoPedido);
        String documento = limpiar(documentoCliente);

        if (codigo == null && documento == null) {
            throw new ParametroInvalidoException("PARAMETRO_OBLIGATORIO", "codigoPedido",
                    "Indique el codigo de pedido o el documento del cliente");
        }
        if (documento != null && !DOCUMENTO.matcher(documento).matches()) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO", "documentoCliente",
                    "El documento debe ser un DNI de 8 digitos o un RUC de 11");
        }
        if (page < 0) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO", "page", "La pagina debe ser 0 o mayor");
        }
        if (size < 1 || size > TAMANO_MAXIMO) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO", "size", "El tamano debe estar entre 1 y 100");
        }

        List<PedidoVentas> encontrados = codigo != null
                ? ventas.consultar(codigo).stream()
                    .filter(p -> documento == null || documento.equals(documentoDe(p)))
                    .toList()
                : ventas.buscarPorDocumento(documento);

        if (encontrados.isEmpty()) {
            throw new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                    "No se encontraron pedidos con los criterios indicados");
        }

        List<PedidoResumenDto> ordenados = encontrados.stream()
                .sorted(Comparator.comparing((PedidoVentas p) -> instante(p.fechaCreacion()),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::resumen)
                .toList();

        int desde = (int) Math.min((long) page * size, ordenados.size());
        int hasta = Math.min(desde + size, ordenados.size());
        log.info("Consulta de pedidos (F6): codigo={} documento={} encontrados={} origen={}",
                codigo, documento != null, ordenados.size(), ventas.origen());
        return new PaginaPedidosResponse(ordenados.subList(desde, hasta), page, size, ordenados.size());
    }

    /**
     * RF-17: detalle del pedido con su historial de estados en orden cronológico ascendente.
     *
     * @throws RecursoNoEncontradoException {@code PEDIDO_NO_ENCONTRADO} si Ventas no conoce el pedido
     */
    public DetallePedidoHistoricoResponse detalle(String pedidoId) {
        PedidoVentas p = ventas.consultar(pedidoId.trim())
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + pedidoId));

        List<EventoEstado> historial = p.historialEstados() == null ? List.of() : p.historialEstados().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((PedidoVentas.Historial h) -> instante(h.fecha()),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(h -> new EventoEstado(h.estado(), enLima(h.fecha()), h.motivo()))
                .toList();

        List<Linea> lineas = p.items() == null ? List.of() : p.items().stream()
                .filter(Objects::nonNull)
                .map(HistorialPedidosService::linea)
                .toList();

        PedidoVentas.Contacto contacto = p.contacto();
        PedidoVentas.Pago pago = p.pago();
        PedidoVentas.Envio envio = p.envio();
        return new DetallePedidoHistoricoResponse(
                p.pedidoId(),
                p.canal(),
                p.tipo(),
                p.estado(),
                pago == null ? null : pago.estado(),
                envio == null ? null : envio.modalidad(),
                envio == null ? null : envio.tiendaId(),
                contacto == null ? null
                        : new Cliente(contacto.nombreCompleto(), contacto.tipoDocumento(), contacto.numeroDocumento()),
                pago == null ? null : pago.total(),
                pago == null ? null : pago.moneda(),
                pago == null ? null : pago.metodoPago(),
                enLima(p.fechaCreacion()),
                enLima(p.ultimaActualizacion()),
                lineas,
                historial);
    }

    private PedidoResumenDto resumen(PedidoVentas p) {
        BigDecimal total = p.pago() == null ? null : p.pago().total();
        return new PedidoResumenDto(p.pedidoId(), p.canal(), enLima(p.fechaCreacion()), p.estado(), total);
    }

    private static Linea linea(PedidoVentas.Item item) {
        int cantidad = item.cantidad() == null ? 0 : item.cantidad();
        BigDecimal precio = item.precioUnitario();
        BigDecimal subtotal = precio == null ? null
                : precio.multiply(BigDecimal.valueOf(cantidad)).setScale(2, RoundingMode.HALF_UP);
        return new Linea(item.sku(), item.descripcion(), null, null, cantidad, precio, subtotal);
    }

    private static String documentoDe(PedidoVentas p) {
        return p.contacto() == null ? null : p.contacto().numeroDocumento();
    }

    private static String limpiar(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    /** Fecha ISO-8601 de Ventas a la hora de Lima; si no se puede leer, se devuelve tal cual. */
    static String enLima(String iso) {
        Instant instante = instante(iso);
        return instante == null ? iso : instante.atZone(LIMA).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** Lee una fecha con zona; una fecha sin zona se toma como UTC (contrato §2.1). */
    static Instant instante(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException conZona) {
            try {
                return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException sinZona) {
                return null;
            }
        }
    }
}

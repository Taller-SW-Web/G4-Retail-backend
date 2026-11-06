package pe.edu.unmsm.fisi.retail.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pe.edu.unmsm.fisi.retail.client.VentasClient;
import pe.edu.unmsm.fisi.retail.dto.CalculoCarritoRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.model.EstadoCajaSesion;
import pe.edu.unmsm.fisi.retail.repository.CajaSesionRepository;
import pe.edu.unmsm.fisi.retail.repository.RegistroVentaRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CobroMostradorService {

    private final AuthService perfiles;
    private final CajaSesionRepository cajas;
    private final ProductosPort productos;
    private final CarritoService carrito;
    private final VentaService calculoVenta;
    private final VentasClient ventas;
    private final RegistroVentaRepository registro;
    private final TransactionTemplate transaccion;
    private final ObjectMapper json;

    private record Lineas(List<VentaRequest.ItemVentaDto> items) {}

    public VentaResponse registrar(UUID usuario, String clave, VentaRequest solicitud) {
        var perfil = perfiles.obtenerPerfil(usuario);
        if (!"VENDEDOR".equals(perfil.perfilTienda()) && !"SUPERVISOR".equals(perfil.perfilTienda())) {
            throw new AccesoDenegadoException("ROL_NO_PERMITIDO", "El perfil no puede registrar ventas");
        }
        String tienda = perfil.tiendaId();
        String huella = huella(new Object[] {tienda, solicitud});
        if (registro.operacion(clave).isEmpty()) {
            turnoAbierto(tienda, solicitud);
            var items = cotizar(solicitud);
            validarPago(conLineas(solicitud, items));
            registro.reservar(clave, usuario, huella, OffsetDateTime.now(ZoneOffset.UTC), serializar(new Lineas(items)));
        }

        // La reserva sobrevive a un fallo de G5. El bloqueo serializa reintentos de una misma clave.
        return transaccion.execute(estado -> {
            var operacion = registro.bloquearOperacion(clave);
            if (!operacion.usuarioId().equals(usuario) || !operacion.huella().equals(huella)) {
                throw new ConflictoException("IDEMPOTENCY_CONFLICT", "La clave ya corresponde a otra venta");
            }
            if (operacion.respuesta() != null) {
                return leer(operacion.respuesta(), VentaResponse.class);
            }
            var peticion = conLineas(solicitud, leer(operacion.lineas(), Lineas.class).items());
            BigDecimal total = validarPago(peticion);
            var fecha = operacion.fecha().withOffsetSameInstant(ZoneOffset.UTC);
            var pedido = calculoVenta.armarPedidoVentasRequest(peticion, total, fecha, tienda);
            var confirmado = ventas.crearPedido(pedido, clave)
                    .filter(r -> r.pedidoId() != null && !r.pedidoId().isBlank())
                    .filter(r -> "PAGADO".equals(r.estado()) || "ENTREGADO".equals(r.estado()))
                    .filter(r -> r.total() != null && total.compareTo(r.total()) == 0)
                    .orElseThrow(() -> new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE",
                            "Ventas no confirmó el cobro. Reintenta la misma operación"));

            String serie = "FACTURA".equals(peticion.comprobante().tipo()) ? "F001" : "B001";
            String correlativo = String.format(Locale.ROOT, "%08d", registro.siguienteCorrelativo(serie));
            var respuesta = calculoVenta.emitirRespuesta(peticion, total, fecha, confirmado.pedidoId(),
                    UUID.randomUUID().toString(), serie, correlativo, confirmado.estado());
            registro.guardar(clave, UUID.fromString(solicitud.turnoId()), tienda, respuesta,
                    peticion.items(), serializar(respuesta));
            return respuesta;
        });
    }

    private void turnoAbierto(String tienda, VentaRequest solicitud) {
        UUID turno;
        try {
            turno = UUID.fromString(solicitud.turnoId());
        } catch (IllegalArgumentException e) {
            throw new ConflictoException("TURNO_NO_ABIERTO", "El turno de caja indicado no es válido");
        }
        cajas.findById(turno)
                .filter(c -> c.getEstado() == EstadoCajaSesion.ABIERTA && c.getTiendaId().equals(tienda))
                .filter(c -> c.getTerminalPosCodigo().equals(solicitud.terminalPos()))
                .orElseThrow(() -> new ConflictoException("TURNO_NO_ABIERTO", "Abre un turno en esta terminal"));
    }

    private List<VentaRequest.ItemVentaDto> cotizar(VentaRequest solicitud) {
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        solicitud.items().forEach(item -> cantidades.merge(item.sku(), item.cantidad(), Math::addExact));
        var items = cantidades.entrySet().stream().map(item -> new CalculoCarritoRequest.ItemCarritoCalculoDto(
                item.getKey(), item.getValue(), productos.consultarPrecio(item.getKey())
                .map(precio -> precio.precioEfectivo()).filter(precio -> precio.signum() >= 0)
                .orElseThrow(() -> new ReglaNegocioException("PRECIO_NO_DISPONIBLE",
                        "No hay precio vigente para " + item.getKey())))).toList();
        return carrito.calcularTotales(new CalculoCarritoRequest(items, solicitud.cupon())).lineas().stream()
                .map(linea -> {
                    try {
                        return new VentaRequest.ItemVentaDto(linea.sku(), linea.cantidad(),
                                linea.precioFinal().divide(BigDecimal.valueOf(linea.cantidad()), 2, RoundingMode.UNNECESSARY));
                    } catch (ArithmeticException e) {
                        throw new ReglaNegocioException("MONTO_NO_DETERMINABLE",
                                "El descuento no se puede atribuir por unidad a " + linea.sku());
                    }
                }).toList();
    }

    private BigDecimal validarPago(VentaRequest peticion) {
        BigDecimal total = calculoVenta.calcularTotal(peticion);
        calculoVenta.validarMontoPago(peticion, total);
        calculoVenta.validarNormativaSunat(peticion, total);
        if ("TARJETA_POS".equals(peticion.pago().medioPago())
                && (peticion.pago().montoTarjeta() == null || total.compareTo(peticion.pago().montoTarjeta()) != 0)) {
            throw new ConflictoException("MONTO_TARJETA_DISTINTO", "El importe de tarjeta difiere del total calculado");
        }
        return total;
    }

    private VentaRequest conLineas(VentaRequest solicitud, List<VentaRequest.ItemVentaDto> items) {
        return new VentaRequest(solicitud.terminalPos(), solicitud.turnoId(), solicitud.clienteId(), items,
                solicitud.pago(), solicitud.comprobante(), solicitud.emitirTicketRegalo(),
                solicitud.cupon(), solicitud.evaluacionId());
    }

    private String huella(Object dato) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serializar(dato).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String serializar(Object dato) {
        try {
            return json.writeValueAsString(dato);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo registrar la venta", e);
        }
    }

    private <T> T leer(String dato, Class<T> tipo) {
        try {
            return json.readValue(dato, tipo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo recuperar la venta registrada", e);
        }
    }
}

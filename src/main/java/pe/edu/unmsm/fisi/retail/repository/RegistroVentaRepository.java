package pe.edu.unmsm.fisi.retail.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RegistroVentaRepository {

    private final JdbcClient jdbc;

    public record Operacion(UUID usuarioId, String huella, OffsetDateTime fecha, String lineas, String respuesta) {}
    public record Venta(UUID id, String pedidoId, String estadoPedido, OffsetDateTime fecha) {}
    public record Linea(String sku, int cantidad, BigDecimal precioPagado) {}

    public Optional<Operacion> operacion(String clave) {
        return consultarOperacion(clave, "");
    }

    public Operacion bloquearOperacion(String clave) {
        return consultarOperacion(clave, " FOR UPDATE").orElseThrow();
    }

    private Optional<Operacion> consultarOperacion(String clave, String bloqueo) {
        return jdbc.sql("""
                SELECT usuario_id, huella, fecha_operacion, lineas, respuesta
                FROM ret_venta_operacion WHERE clave = ?
                """ + bloqueo).param(clave).query((fila, indice) -> new Operacion(
                fila.getObject("usuario_id", UUID.class), fila.getString("huella"),
                fila.getObject("fecha_operacion", OffsetDateTime.class), fila.getString("lineas"),
                fila.getString("respuesta"))).optional();
    }

    public void reservar(String clave, UUID usuario, String huella, OffsetDateTime fecha, String lineas) {
        jdbc.sql("""
                INSERT INTO ret_venta_operacion (clave, usuario_id, huella, fecha_operacion, lineas)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """).params(clave, usuario, huella, fecha, lineas).update();
    }

    public int siguienteCorrelativo(String serie) {
        String secuencia = "B001".equals(serie) ? "ret_comprobante_b001_seq" : "ret_comprobante_f001_seq";
        return jdbc.sql("SELECT nextval('" + secuencia + "')").query(Integer.class).single();
    }

    public void guardar(String clave, UUID turno, String tienda, VentaResponse venta,
                        List<VentaRequest.ItemVentaDto> lineas, String respuesta) {
        var comprobante = venta.comprobante();
        UUID ventaId = UUID.fromString(venta.ventaId());
        jdbc.sql("""
                INSERT INTO ret_venta (id_venta, caja_sesion_id, tienda_id, pedido_id, estado_pedido, serie,
                    correlativo, fecha_emision, total)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(ventaId, turno, tienda, venta.pedidoId(), venta.estadoPedido(), comprobante.serie(),
                Integer.parseInt(comprobante.correlativo()), comprobante.fechaEmision(), comprobante.total()).update();
        for (int indice = 0; indice < lineas.size(); indice++) {
            var linea = lineas.get(indice);
            jdbc.sql("""
                    INSERT INTO ret_venta_item (venta_id, numero_linea, sku, cantidad, precio_unitario_pagado)
                    VALUES (?, ?, ?, ?, ?)
                    """).params(ventaId, indice + 1, linea.sku(), linea.cantidad(), linea.precioFinal()).update();
        }
        jdbc.sql("UPDATE ret_venta_operacion SET respuesta = ? WHERE clave = ?").params(respuesta, clave).update();
    }

    public Optional<Venta> buscarBoleta(String tienda, int correlativo, boolean bloquear) {
        String consulta = """
                SELECT id_venta, pedido_id, estado_pedido, fecha_emision FROM ret_venta
                WHERE tienda_id = ? AND serie = 'B001' AND correlativo = ?
                """;
        return jdbc.sql(bloquear ? consulta + " FOR UPDATE" : consulta).params(tienda, correlativo)
                .query((fila, indice) -> new Venta(fila.getObject("id_venta", UUID.class),
                        fila.getString("pedido_id"), fila.getString("estado_pedido"),
                        fila.getObject("fecha_emision", OffsetDateTime.class))).optional();
    }

    public List<Linea> lineas(UUID venta) {
        return jdbc.sql("""
                SELECT sku, cantidad, precio_unitario_pagado FROM ret_venta_item
                WHERE venta_id = ? ORDER BY numero_linea
                """).param(venta).query((fila, indice) -> new Linea(fila.getString("sku"),
                fila.getInt("cantidad"), fila.getBigDecimal("precio_unitario_pagado"))).list();
    }
}

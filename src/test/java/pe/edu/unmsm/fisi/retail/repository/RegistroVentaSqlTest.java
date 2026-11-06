package pe.edu.unmsm.fisi.retail.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RegistroVentaSqlTest {

    @TempDir
    Path directorio;

    @Test
    void recuperaLaCompraPorBoletaTrasReabrirLaBaseYNoRepiteElCorrelativo() throws Exception {
        String url = "jdbc:h2:file:" + directorio.resolve("retail") + ";MODE=PostgreSQL";
        String migracion = Files.readString(Path.of("diseño_bd/migracion-f5-registro-venta.sql"));
        String ddl = migracion.replaceAll("(?m)^ALTER TABLE .+ ENABLE ROW LEVEL SECURITY;\\s*$", "");
        UUID turno = UUID.randomUUID();
        UUID venta = UUID.randomUUID();

        try (var conexion = DriverManager.getConnection(url, "sa", "");
             var sql = conexion.createStatement()) {
            sql.execute("CREATE TABLE ret_caja_sesion (id_sesion UUID PRIMARY KEY)");
            ScriptUtils.executeSqlScript(conexion, new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));
            sql.executeUpdate("INSERT INTO ret_caja_sesion VALUES ('" + turno + "')");
            try (var insertar = conexion.prepareStatement("""
                    INSERT INTO ret_venta (id_venta, caja_sesion_id, tienda_id, pedido_id, serie,
                        correlativo, fecha_emision, total)
                    VALUES (?, ?, 'TIENDA-MIRAFLORES', 'PED-2026-00412', 'B001',
                        nextval('ret_comprobante_b001_seq'), CURRENT_TIMESTAMP, 159.80)
                    """)) {
                insertar.setObject(1, venta);
                insertar.setObject(2, turno);
                insertar.executeUpdate();
            }
            try (var insertar = conexion.prepareStatement("""
                    INSERT INTO ret_venta_item (venta_id, numero_linea, sku, cantidad, precio_unitario_pagado)
                    VALUES (?, 1, 'CAM-M-AZUL', 2, 79.90)
                    """)) {
                insertar.setObject(1, venta);
                insertar.executeUpdate();
            }
        }

        try (var conexion = DriverManager.getConnection(url, "sa", "");
             var sql = conexion.createStatement();
             var compra = sql.executeQuery("""
                     SELECT v.pedido_id, i.sku, i.cantidad, i.precio_unitario_pagado
                     FROM ret_venta v JOIN ret_venta_item i ON i.venta_id = v.id_venta
                     WHERE v.tienda_id = 'TIENDA-MIRAFLORES' AND v.serie = 'B001' AND v.correlativo = 1
                     """)) {
            assertThat(compra.next()).isTrue();
            assertThat(compra.getString("pedido_id")).isEqualTo("PED-2026-00412");
            assertThat(compra.getString("sku")).isEqualTo("CAM-M-AZUL");
            assertThat(compra.getInt("cantidad")).isEqualTo(2);
            assertThat(compra.getBigDecimal("precio_unitario_pagado")).isEqualByComparingTo("79.90");
            assertThat(compra.next()).isFalse();
        }

        try (var conexion = DriverManager.getConnection(url, "sa", "");
             var sql = conexion.createStatement();
             var siguiente = sql.executeQuery("SELECT nextval('ret_comprobante_b001_seq')")) {
            assertThat(siguiente.next()).isTrue();
            assertThat(siguiente.getInt(1)).isGreaterThan(1);
        }
    }
}

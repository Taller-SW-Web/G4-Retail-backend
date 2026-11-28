package pe.edu.unmsm.fisi.retail.integracion.inventario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Traducción de la incidencia de Retail al {@code CrearIncidenciaInventarioRequest} de G6 (§5.12). */
@DisplayName("IncidenciaReportadaPayload — formato del OpenAPI de G6")
class IncidenciaReportadaPayloadTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "MANCHADO_PROBADOR, DANIO",
            "COSTURA_ROTA, DANIO",
            "DEFECTO_FABRICA, DANIO",
            "EXTRAVIO_NO_UBICADO, NO_UBICADA",
            "OTRA_COSA, OTRO_FISICO"
    })
    @DisplayName("Traduce el tipo de falla de Retail al tipo de incidencia de Inventory")
    void traduceTipo(String retail, String inventory) {
        assertThat(IncidenciaReportadaPayload.tipoInventory(retail)).isEqualTo(inventory);
    }

    @Test
    @DisplayName("Serializa en snake_case con los campos obligatorios de G6 y una unidad por prenda")
    void serializaComoG6() throws Exception {
        UUID incidencia = UUID.fromString("5b0e3f0a-6c1d-4d3e-9a2b-1f0c7e8d9a10");
        UUID vendedor = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String json = mapper.writeValueAsString(IncidenciaReportadaPayload.desdeRetail(incidencia, "TIENDA-MIRAFLORES",
                "CAM-PERU-M-BLA", "EXTRAVIO_NO_UBICADO", OffsetDateTime.parse("2026-11-26T15:30:00Z"), vendedor));

        assertThat(json)
                .contains("\"operation_id\":\"5b0e3f0a-6c1d-4d3e-9a2b-1f0c7e8d9a10\"")
                .contains("\"external_incident_id\":\"5b0e3f0a-6c1d-4d3e-9a2b-1f0c7e8d9a10\"")
                .contains("\"quantity\":1")
                .contains("\"location_id\":\"TIENDA-MIRAFLORES\"")
                .contains("\"type\":\"NO_UBICADA\"")
                .contains("\"occurred_at\":\"2026-11-26T15:30:00Z\"")
                .contains("\"actor_ref\":\"bbbbbbbb-0000-0000-0000-000000000001\"");
    }
}

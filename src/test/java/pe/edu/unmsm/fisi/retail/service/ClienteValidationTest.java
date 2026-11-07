package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import pe.edu.unmsm.fisi.retail.controller.ClienteController;
import pe.edu.unmsm.fisi.retail.dto.ClienteAltaRapidaRequest;
import pe.edu.unmsm.fisi.retail.dto.ClienteResponse;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.DatoInvalidoException;
import pe.edu.unmsm.fisi.retail.exception.GlobalExceptionHandler;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.service.ValidadorDocumento.DocumentoValidado;
import pe.edu.unmsm.fisi.retail.service.ValidadorDocumento.TipoDocumento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bateria de validacion estricta de documentos de identidad (F3, RF-09).
 *
 * <p>Cubre las cuatro reglas homologadas con G5/G7 (DNI, RUC, CE y PASAPORTE) con documentos
 * validos, longitudes incorrectas y caracteres prohibidos, y comprueba que el padron y la API
 * rechacen el formato invalido con {@code 400 DATO_INVALIDO}.</p>
 */
@DisplayName("F3 RF-09 - Validacion estricta de documentos")
class ClienteValidationTest {

    @Nested
    @DisplayName("ValidadorDocumento")
    class Validador {

        @ParameterizedTest(name = "{0} {1} es valido")
        @CsvSource({
                "DNI,       12345678",
                "DNI,       00000001",
                "RUC,       10456789123",
                "RUC,       20100047218",
                "CE,        001234567",
                "CE,        AB1234567890",
                "PASAPORTE, A12345",
                "PASAPORTE, AB12345678901234",
                "dni,       72345678",
                "pasaporte, xy987654"
        })
        void aceptaDocumentosValidos(String tipo, String numero) {
            DocumentoValidado documento = ValidadorDocumento.validar(tipo, numero);

            assertThat(documento.tipo().name()).isEqualTo(tipo.toUpperCase());
        }

        @ParameterizedTest(name = "{0} con {2} caracteres ({1}) se rechaza")
        @CsvSource({
                "DNI,       1234567,           7",
                "DNI,       123456789,         9",
                "RUC,       2010004721,        10",
                "RUC,       201000472189,      12",
                "CE,        12345678,          8",
                "CE,        1234567890123,     13",
                "PASAPORTE, A1234,             5",
                "PASAPORTE, A1234567890123456, 17"
        })
        void rechazaLongitudesIncorrectas(String tipo, String numero, int longitud) {
            assertThat(numero).hasSize(longitud);

            assertThatThrownBy(() -> ValidadorDocumento.validar(tipo, numero))
                    .isInstanceOf(DatoInvalidoException.class)
                    .extracting("campo").isEqualTo("numeroDocumento");
        }

        @ParameterizedTest(name = "DNI \"{0}\" con caracteres prohibidos se rechaza")
        @ValueSource(strings = {
                "1234567A",    // letra
                "1234 5678",   // espacio intermedio
                " 12345678",   // espacio inicial
                "12345678 ",   // espacio final
                "1234-567",    // guion
                "12.345.67",   // puntos
                "-1234567",    // signo
                "\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668" // digitos arabigo-indicos: no son ASCII 0-9
        })
        void dniRechazaCaracteresProhibidos(String numero) {
            assertThatThrownBy(() -> ValidadorDocumento.validar("DNI", numero))
                    .isInstanceOf(DatoInvalidoException.class);
        }

        @ParameterizedTest(name = "RUC {0} con prefijo no autorizado se rechaza")
        @ValueSource(strings = {"15123456789", "17123456789", "30123456789", "11123456789", "00123456789"})
        void rucRechazaPrefijosNoAutorizados(String numero) {
            assertThatThrownBy(() -> ValidadorDocumento.validar("RUC", numero))
                    .isInstanceOf(DatoInvalidoException.class)
                    .hasMessageContaining("10 o 20");
        }

        @ParameterizedTest(name = "{0} \"{1}\" con caracteres no alfanumericos se rechaza")
        @CsvSource({
                "CE,        'AB-1234567'",
                "CE,        'AB 1234567'",
                "CE,        '\u00D1A12345678'",
                "PASAPORTE, 'AB#12345'",
                "PASAPORTE, 'AB.12345'",
                "PASAPORTE, '\u00C1B123456'"
        })
        void ceYPasaporteRechazanSimbolos(String tipo, String numero) {
            assertThatThrownBy(() -> ValidadorDocumento.validar(tipo, numero))
                    .isInstanceOf(DatoInvalidoException.class);
        }

        @ParameterizedTest(name = "numero de documento vacio o nulo se rechaza")
        @NullAndEmptySource
        void rechazaNumeroAusente(String numero) {
            assertThatThrownBy(() -> ValidadorDocumento.validar("DNI", numero))
                    .isInstanceOf(DatoInvalidoException.class)
                    .extracting("campo").isEqualTo("numeroDocumento");
        }

        @ParameterizedTest(name = "tipo de documento \"{0}\" no admitido se rechaza")
        @ValueSource(strings = {"LIBRETA", "DNIE", "PAS", " "})
        void rechazaTiposNoAdmitidos(String tipo) {
            assertThatThrownBy(() -> ValidadorDocumento.validar(tipo, "12345678"))
                    .isInstanceOf(DatoInvalidoException.class)
                    .extracting("campo").isEqualTo("tipoDocumento");
        }

        @Test
        @DisplayName("CE y pasaporte se normalizan a mayusculas; DNI y RUC quedan intactos")
        void normalizaAlfanumericos() {
            assertThat(ValidadorDocumento.validar("CE", "ab1234567").numero()).isEqualTo("AB1234567");
            assertThat(ValidadorDocumento.validar("PASAPORTE", "xy987654").numero()).isEqualTo("XY987654");
            assertThat(ValidadorDocumento.validar("DNI", "12345678").numero()).isEqualTo("12345678");
        }

        @Test
        @DisplayName("Cada tipo usa la expresion regular oficial")
        void cadaTipoUsaSuRegex() {
            assertThat(TipoDocumento.DNI.admite("12345678")).isTrue();
            assertThat(TipoDocumento.RUC.admite("12345678")).isFalse();
            assertThat(TipoDocumento.CE.admite("12345678")).isFalse();
            assertThat(TipoDocumento.PASAPORTE.admite("12345678")).isTrue();
        }
    }

    @Nested
    @DisplayName("ClienteServiceImpl")
    class Servicio {

        private ClienteServiceImpl servicio;

        @BeforeEach
        void crearPadron() {
            servicio = new ClienteServiceImpl();
        }

        private ClienteAltaRapidaRequest alta(String tipo, String numero) {
            return new ClienteAltaRapidaRequest("RETAIL", tipo, numero, "Rosa", "Huaman",
                    "rosa@correo.com", "987654321");
        }

        @ParameterizedTest(name = "busqueda con {0} \"{1}\" invalido responde DATO_INVALIDO")
        @CsvSource({"DNI, 1234567", "RUC, 30123456789", "CE, 123", "PASAPORTE, AB#123", "LIBRETA, 12345678"})
        void busquedaRechazaFormatoInvalido(String tipo, String numero) {
            assertThatThrownBy(() -> servicio.buscarPorDocumento(tipo, numero))
                    .isInstanceOf(DatoInvalidoException.class);
        }

        @ParameterizedTest(name = "alta con {0} \"{1}\" invalido no registra al cliente")
        @CsvSource({"DNI, 1234567A", "RUC, 15123456789", "CE, AB-1234567", "PASAPORTE, A1"})
        void altaRechazaFormatoInvalido(String tipo, String numero) {
            assertThatThrownBy(() -> servicio.registrarAltaRapida(alta(tipo, numero)))
                    .isInstanceOf(DatoInvalidoException.class);
        }

        @ParameterizedTest(name = "alta de {0} {1} y busqueda posterior")
        @CsvSource({"DNI, 45678912", "RUC, 20100047218", "CE, 000987654", "PASAPORTE, P1234567"})
        void altaValidaQuedaDisponible(String tipo, String numero) {
            ClienteResponse registrado = servicio.registrarAltaRapida(alta(tipo, numero));

            assertThat(registrado.tipoDocumento()).isEqualTo(tipo);
            assertThat(servicio.buscarPorDocumento(tipo, numero)).isEqualTo(registrado);
        }

        @Test
        @DisplayName("Un pasaporte con los mismos digitos que un DNI es otro cliente")
        void elTipoFormaParteDeLaIdentidad() {
            servicio.registrarAltaRapida(alta("PASAPORTE", "12345678"));

            assertThat(servicio.buscarPorDocumento("DNI", "12345678").nombreCompleto()).isEqualTo("Ana Quispe");
            assertThat(servicio.buscarPorDocumento("PASAPORTE", "12345678").nombreCompleto())
                    .isEqualTo("Rosa Huaman");
        }

        @Test
        @DisplayName("El CE se encuentra aunque se escriba en minusculas")
        void ceSinDistinguirMayusculas() {
            servicio.registrarAltaRapida(alta("CE", "ab1234567"));

            assertThat(servicio.buscarPorDocumento("CE", "AB1234567").numeroDocumento()).isEqualTo("AB1234567");
            assertThatThrownBy(() -> servicio.registrarAltaRapida(alta("CE", "AB1234567")))
                    .isInstanceOf(ConflictoException.class);
        }

        @Test
        @DisplayName("Un documento valido que no esta en el padron sigue respondiendo 404")
        void documentoValidoInexistente() {
            assertThatThrownBy(() -> servicio.buscarPorDocumento("DNI", "99999999"))
                    .isInstanceOf(RecursoNoEncontradoException.class);
        }
    }

    @Nested
    @DisplayName("API /api/v1/retail/clientes con el padron real")
    class Api {

        private MockMvc mockMvc;

        @BeforeEach
        void montarApi() {
            mockMvc = MockMvcBuilders
                    .standaloneSetup(new ClienteController(new ClienteServiceImpl()))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @ParameterizedTest(name = "GET con {0} \"{1}\" responde 400 DATO_INVALIDO")
        @CsvSource({"DNI, 1234567", "DNI, 1234567A", "RUC, 30123456789", "CE, 12345678", "PASAPORTE, A1234"})
        void busquedaInvalidaResponde400(String tipo, String numero) throws Exception {
            mockMvc.perform(get("/api/v1/retail/clientes")
                            .param("tipoDocumento", tipo)
                            .param("numeroDocumento", numero))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DATO_INVALIDO"))
                    .andExpect(jsonPath("$.errores[0].campo").value("numeroDocumento"));
        }

        @Test
        @DisplayName("GET con tipo no admitido responde 400 DATO_INVALIDO sobre tipoDocumento")
        void tipoNoAdmitidoResponde400() throws Exception {
            mockMvc.perform(get("/api/v1/retail/clientes")
                            .param("tipoDocumento", "LIBRETA")
                            .param("numeroDocumento", "12345678"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DATO_INVALIDO"))
                    .andExpect(jsonPath("$.errores[0].campo").value("tipoDocumento"));
        }

        @Test
        @DisplayName("POST con RUC de prefijo no autorizado responde 400 DATO_INVALIDO")
        void altaConRucInvalidoResponde400() throws Exception {
            String cuerpo = """
                    {"tipoDocumento":"RUC","numeroDocumento":"30123456789",
                     "nombres":"Comercial","apellidos":"Andina"}
                    """;

            mockMvc.perform(post("/api/v1/retail/clientes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DATO_INVALIDO"));
        }

        private String altaConEmail(String email) {
            return """
                    {"tipoDocumento":"DNI","numeroDocumento":"45678912",
                     "nombres":"Rosa","apellidos":"Huaman","email":"%s"}
                    """.formatted(email);
        }

        @ParameterizedTest(name = "POST con email \"{0}\" responde 400")
        @ValueSource(strings = {
                "rosa@correo..com",     // puntos consecutivos en el dominio
                "rosa@.correo.com",     // dominio que empieza con punto
                "rosa@correo.com.",     // dominio que termina en punto
                "rosa@-correo.com",     // etiqueta que empieza con guion
                "rosa@correo-.com",     // etiqueta que termina en guion
                "rosa@correo.c",        // TLD de una letra
                "rosa@correo.c0m",      // TLD con digitos
                "rosa@correo",          // dominio sin punto
                "rosa..huaman@correo.com", // puntos consecutivos en la parte local
                ".rosa@correo.com",     // parte local que empieza con punto
                "rosa.@correo.com",     // parte local que termina en punto
                "rosa@@correo.com",     // doble arroba
                "rosa huaman@correo.com", // espacio
                "rosa@correo .com"
        })
        void altaConEmailInvalidoResponde400(String email) throws Exception {
            mockMvc.perform(post("/api/v1/retail/clientes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(altaConEmail(email)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"))
                    .andExpect(jsonPath("$.errores[0].campo").value("email"));
        }

        @ParameterizedTest(name = "POST con email \"{0}\" responde 201")
        @ValueSource(strings = {
                "rosa@correo.com",
                "rosa.huaman@correo.com.pe",
                "rosa+tienda@mi-correo.pe",
                "r_h-2026@sub.dominio.org"
        })
        void altaConEmailValidoResponde201(String email) throws Exception {
            mockMvc.perform(post("/api/v1/retail/clientes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(altaConEmail(email)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("POST con pasaporte valido responde 201")
        void altaConPasaporteValidoResponde201() throws Exception {
            String cuerpo = """
                    {"tipoDocumento":"PASAPORTE","numeroDocumento":"P1234567",
                     "nombres":"John","apellidos":"Smith"}
                    """;

            mockMvc.perform(post("/api/v1/retail/clientes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tipoDocumento").value("PASAPORTE"))
                    .andExpect(jsonPath("$.numeroDocumento").value("P1234567"));
        }
    }
}

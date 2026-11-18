package cl.minimarket.pos.shared.error;

import cl.minimarket.pos.shared.rut.ValidRut;
import cl.minimarket.pos.shared.web.SortParser;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba del {@link GlobalExceptionHandler} con un {@code @WebMvcTest} mínimo: solo se cargan el
 * controlador de prueba y el handler (sin escaneo del resto de la aplicación ni base de datos).
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {GlobalExceptionHandlerTest.ProbeController.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void setTrace() {
        MDC.put("traceId", "trace-123");
    }

    @AfterEach
    void clearTrace() {
        MDC.clear();
    }

    @Test
    void validationErrorListsEveryFieldWithCode() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0,\"rut\":\"12.345.678-0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://pos-minimarket/errors/validation-error"))
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.traceId").value("trace-123"))
                .andExpect(jsonPath("$.instance").value("/probe/validate"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[*].field").value(
                        org.hamcrest.Matchers.containsInAnyOrder("name", "quantity", "rut")))
                .andExpect(jsonPath("$.errors[*].code").value(hasItem("NOT_BLANK")))
                .andExpect(jsonPath("$.errors[*].code").value(hasItem("MIN")))
                .andExpect(jsonPath("$.errors[*].code").value(hasItem("VALID_RUT")))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void businessExceptionUsesCatalogStatusAndExtensions() throws Exception {
        mvc.perform(get("/probe/business"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://pos-minimarket/errors/override-required"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("OVERRIDE_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("Se requiere autorización para aplicar el descuento."))
                .andExpect(jsonPath("$.action").value("DISCOUNT_OVER_LIMIT"))
                .andExpect(jsonPath("$.context.discountPercent").value(25))
                .andExpect(jsonPath("$.traceId").value("trace-123"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void traceIdIsOmittedWhenThereIsNoneInMdc() throws Exception {
        MDC.clear();

        mvc.perform(get("/probe/business"))
                .andExpect(jsonPath("$.traceId").doesNotExist());
    }

    @Test
    void notAllowedSortFieldIsAValidationError() throws Exception {
        mvc.perform(get("/probe/sorted").param("sort", "costPrice,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("sort"))
                .andExpect(jsonPath("$.errors[0].code").value("SORT_FIELD_NOT_ALLOWED"));
    }

    @Test
    void constraintViolationExceptionBecomesValidationError() throws Exception {
        mvc.perform(get("/probe/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    void methodParameterConstraintIsAValidationError() throws Exception {
        mvc.perform(get("/probe/param").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].code").value("MIN"));
    }

    @Test
    void unreadableJsonIsAValidationError() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value(not(containsString("Jackson"))));
    }

    @Test
    void parameterTypeMismatchIsAValidationError() throws Exception {
        mvc.perform(get("/probe/param").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].code").value("TYPE_MISMATCH"));
    }

    @Test
    void missingRequiredParameterIsAValidationError() throws Exception {
        mvc.perform(get("/probe/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("https://pos-minimarket/errors/not-found"));
    }

    @Test
    void wrongHttpMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(post("/probe/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void accessDeniedIs403() throws Exception {
        mvc.perform(get("/probe/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.type").value("https://pos-minimarket/errors/access-denied"));
    }

    @Test
    void optimisticLockIsConcurrentModification() throws Exception {
        mvc.perform(get("/probe/locked"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedExceptionIs500WithoutInternals() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").value("trace-123"))
                .andExpect(content().string(not(containsString("secret-db-password"))))
                .andExpect(content().string(not(containsString("stackTrace"))))
                .andExpect(content().string(not(containsString("at cl.minimarket"))));
    }

    @Test
    void uniqueViolationIsDuplicatedValueWithoutLeakingConstraint() throws Exception {
        mvc.perform(get("/probe/integrity").param("state", "23505"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"))
                .andExpect(content().string(not(containsString("uq_products_sku"))))
                .andExpect(content().string(not(containsString("insert into"))));
    }

    @Test
    void checkViolationIsValidationError() throws Exception {
        mvc.perform(get("/probe/integrity").param("state", "23514"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(not(containsString("uq_products_sku"))));
    }

    @Test
    void foreignKeyViolationIsEntityInUse() throws Exception {
        mvc.perform(get("/probe/integrity").param("state", "23503"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));
    }

    @Test
    void otherIntegrityViolationIs500() throws Exception {
        mvc.perform(get("/probe/integrity").param("state", "23502"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("uq_products_sku"))));
    }

    @Test
    void methodConstraintExposesOnlyTheParameterName() throws Exception {
        mvc.perform(get("/probe/method-constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(content().string(not(containsString("create"))))
                .andExpect(content().string(not(containsString("arg0"))));
    }

    @Test
    void oversizedUploadIsFileInvalidWithoutInternalData() throws Exception {
        mvc.perform(get("/probe/too-large"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FILE_INVALID"))
                .andExpect(jsonPath("$.type").value("https://pos-minimarket/errors/file-invalid"))
                .andExpect(jsonPath("$.detail").value("El archivo supera el tamaño máximo permitido."))
                .andExpect(jsonPath("$.traceId").value("trace-123"))
                .andExpect(content().string(not(containsString("1048576"))));
    }

    /** Request de prueba con tres reglas de validación. */
    record ProbeRequest(@NotBlank String name, @Min(1) int quantity, @ValidRut String rut) {
    }

    /** Servicio de prueba para validar parámetros de método (el path trae el nombre del método). */
    static class Service {
        void create(@NotBlank String name) {
        }
    }

    /** Controlador de prueba que dispara cada tipo de excepción. */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        private static final SortParser SORT = SortParser.allowing("name", "sku");

        private final Validator validator;

        ProbeController(Validator validator) {
            this.validator = validator;
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody ProbeRequest request) {
            return "ok";
        }

        @GetMapping("/business")
        String business() {
            throw new BusinessException(ErrorCode.OVERRIDE_REQUIRED,
                    "Se requiere autorización para aplicar el descuento.",
                    Map.of("action", "DISCOUNT_OVER_LIMIT", "context", Map.of("discountPercent", 25)));
        }

        @GetMapping("/sorted")
        String sorted(@RequestParam(required = false) List<String> sort) {
            SORT.parse(sort);
            return "ok";
        }

        @GetMapping("/constraint")
        String constraint() {
            Set<ConstraintViolation<ProbeRequest>> violations = validator.validate(new ProbeRequest("", 0, "1-1"));
            throw new ConstraintViolationException(violations);
        }

        @GetMapping("/integrity")
        String integrity(@RequestParam String state) {
            throw new DataIntegrityViolationException("insert into products ... uq_products_sku",
                    new java.sql.SQLException("duplicate key uq_products_sku", state));
        }

        @GetMapping("/method-constraint")
        String methodConstraint() throws NoSuchMethodException {
            Method method = Service.class.getDeclaredMethod("create", String.class);
            throw new ConstraintViolationException(
                    validator.forExecutables().validateParameters(new Service(), method, new Object[]{""}));
        }

        @GetMapping("/param")
        String param(@RequestParam @Min(1) int size) {
            return "ok";
        }

        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("detalle interno");
        }

        @GetMapping("/locked")
        String locked() {
            throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
        }

        @GetMapping("/too-large")
        String tooLarge() {
            throw new MaxUploadSizeExceededException(1_048_576L);
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret-db-password");
        }
    }
}

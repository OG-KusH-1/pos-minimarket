package cl.minimarket.pos.shared.web;

import cl.minimarket.pos.shared.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Verifica que el filtro publique el traceId y que coincida con el de los ProblemDetail. */
@WebMvcTest(controllers = TraceIdFilterTest.Controller.class)
@ContextConfiguration(classes = {TraceIdFilterTest.Controller.class, GlobalExceptionHandler.class})
class TraceIdFilterTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new TraceIdFilter()).build();
    }

    @Test
    void errorBodyTraceIdMatchesHeaderFor500() throws Exception {
        MvcResult result = mvc.perform(get("/trace/boom")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        String header = result.getResponse().getHeader("X-Trace-Id");
        assertThat(header).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).contains("\"traceId\":\"" + header + "\"");
    }

    @Test
    void errorBodyTraceIdMatchesHeaderFor400() throws Exception {
        MvcResult result = mvc.perform(get("/trace/number").param("n", "x")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String header = result.getResponse().getHeader("X-Trace-Id");
        assertThat(header).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).contains("\"traceId\":\"" + header + "\"");
    }

    @Test
    void validIncomingRequestIdIsKeptAndUsedInTheBody() throws Exception {
        MvcResult result = mvc.perform(get("/trace/boom").header("X-Request-Id", "req-ABC-12345"))
                .andExpect(jsonPath("$.traceId").value("req-ABC-12345")).andReturn();

        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo("req-ABC-12345");
    }

    @Test
    void invalidIncomingRequestIdIsReplacedByUuid() throws Exception {
        for (String invalid : new String[]{"short", "has space in it!", "a".repeat(41), "bad-id-é-12345"}) {
            MvcResult result = mvc.perform(get("/trace/ok").header("X-Request-Id", invalid)).andReturn();

            assertThat(result.getResponse().getHeader("X-Trace-Id")).isNotEqualTo(invalid)
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }
    }

    @Test
    void mdcIsClearedAfterTheRequest() throws Exception {
        mvc.perform(get("/trace/ok"));

        assertThat(MDC.get("traceId")).isNull();
    }

    @RestController
    @RequestMapping("/trace")
    static class Controller {
        @GetMapping("/ok")
        String ok() {
            return "ok";
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("x");
        }

        @GetMapping("/number")
        String number(@RequestParam int n) {
            return "ok";
        }
    }
}

package cl.minimarket.pos.files;

import cl.minimarket.pos.files.application.FileCleanupService;
import cl.minimarket.pos.files.infrastructure.FileValidator;
import cl.minimarket.pos.files.infrastructure.FilesProperties;
import cl.minimarket.pos.shared.context.support.ConfigurableStoreAccessValidator;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del módulo {@code files}: subida con validación de magic bytes, descarga
 * segura con {@code nosniff} y limpieza física de archivos marcados con {@code deleted_at}.
 */
@Import(FileControllerIT.StoreValidatorConfig.class)
class FileControllerIT extends AbstractIntegrationIT {

    @TestConfiguration
    static class StoreValidatorConfig {
        @Bean
        ConfigurableStoreAccessValidator storeAccessValidator() {
            return new ConfigurableStoreAccessValidator();
        }
    }

    private static final byte[] MINIMAL_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89
    };

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    FileApi fileApi;

    @Autowired
    FileCleanupService cleanupService;

    @Autowired
    FilesProperties filesProperties;

    @Autowired
    ConfigurableStoreAccessValidator validator;

    private long storeId;

    @BeforeEach
    void setup() {
        validator.reset();
        storeId = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
    }

    @Test
    void uploadsValidPngAndServesItWithNosniff() throws Exception {
        MockMultipartFile png = new MockMultipartFile(
                "file", "producto.png", "image/png", MINIMAL_PNG);

        String body = mvc.perform(multipart("/api/v1/files")
                        .file(png)
                        .param("category", "PRODUCT_IMAGE")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.category").value("PRODUCT_IMAGE"))
                .andExpect(jsonPath("$.url").isString())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = jsonMapper.readTree(body);
        long fileId = json.get("id").asLong();
        String url = json.get("url").asString();

        mvc.perform(get(url)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(MINIMAL_PNG));

        assertThat(fileApi.get(fileId).sizeBytes()).isEqualTo(MINIMAL_PNG.length);
    }

    @Test
    void rejectsExeRenamedAsPngWithFileInvalid() throws Exception {
        byte[] fakeExe = new byte[]{'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00};
        MockMultipartFile exe = new MockMultipartFile(
                "file", "malware.png", "image/png", fakeExe);

        mvc.perform(multipart("/api/v1/files")
                        .file(exe)
                        .param("category", "PRODUCT_IMAGE")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FILE_INVALID"));
    }

    @Test
    void rejectsUploadWithoutRequiredCategoryPermission() throws Exception {
        MockMultipartFile png = new MockMultipartFile(
                "file", "logo.png", "image/png", MINIMAL_PNG);

        mvc.perform(multipart("/api/v1/files")
                        .file(png)
                        .param("category", "COMPANY_LOGO")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void acceptsPngNearFiveMegabytes() throws Exception {
        int nearMaxBytes = (int) FileValidator.MAX_IMAGE_BYTES - 1024;
        MockMultipartFile png = new MockMultipartFile(
                "file", "grande.png", "image/png", pngOfSize(nearMaxBytes));

        mvc.perform(multipart("/api/v1/files")
                        .file(png)
                        .param("category", "PRODUCT_IMAGE")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sizeBytes").value(nearMaxBytes));
    }

    @Test
    void rejectsPngOverFiveMegabytesWithFileInvalid() throws Exception {
        int overMaxBytes = (int) FileValidator.MAX_IMAGE_BYTES + 1;
        MockMultipartFile png = new MockMultipartFile(
                "file", "enorme.png", "image/png", pngOfSize(overMaxBytes));

        mvc.perform(multipart("/api/v1/files")
                        .file(png)
                        .param("category", "PRODUCT_IMAGE")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FILE_INVALID"));
    }

    private static byte[] pngOfSize(int totalBytes) {
        byte[] content = new byte[totalBytes];
        System.arraycopy(MINIMAL_PNG, 0, content, 0, MINIMAL_PNG.length);
        return content;
    }

    @Test
    void cleanupJobDeletesBinaryOfMarkedFiles() {
        byte[] csv = "sku,name\n100,Test\n".getBytes();
        FileMetadata meta = fileApi.register(
                new ByteArrayInputStream(csv), "import.csv", "text/csv", csv.length, FileCategory.IMPORT);

        Path binaryPath = filesProperties.root().toAbsolutePath().normalize().resolve(meta.storageKey());
        assertThat(Files.exists(binaryPath)).isTrue();

        appJdbc().sql("UPDATE stored_files SET deleted_at = :now WHERE id = :id")
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .param("id", meta.id())
                .update();

        int processed = cleanupService.deleteMarkedBinaries();
        assertThat(processed).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(binaryPath)).isFalse();
    }
}

package cl.minimarket.pos.catalog.scale.application;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.product.application.CodeResolver;
import cl.minimarket.pos.catalog.product.application.ProductSnapshotMapper;
import cl.minimarket.pos.catalog.product.domain.Product;
import cl.minimarket.pos.catalog.product.domain.ProductRepository;
import cl.minimarket.pos.catalog.scale.domain.ScaleBarcodeConfig;
import cl.minimarket.pos.catalog.scale.domain.ScaleBarcodeConfigRepository;
import cl.minimarket.pos.catalog.scale.domain.ScaleBarcodeDecoder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Implementación definitiva de {@link CodeResolver} con soporte completo para códigos de balanza
 * EAN-13 ({@code HU-CAT-05}, {@code API.md} F.2).
 *
 * <p>Orden de resolución:
 * <ol>
 *   <li>Código de barras regular ({@code barcode_type <> 'SCALE_PLU'}) en productos activos.</li>
 *   <li>Código de balanza EAN-13 contra {@code scale_barcode_configs} activas (valida dígito
 *       verificador EAN-13, extrae PLU y busca {@code barcode_type = 'SCALE_PLU'}).</li>
 *   <li>Código de atajo ({@code shortcut_code}) en productos activos.</li>
 *   <li>SKU exacto en productos activos.</li>
 * </ol>
 *
 * <p>Reemplaza al bean por defecto de {@code ProductCatalogBeansConfiguration} al registrarse en el
 * contexto.
 */
@Component
public class ScaleAwareCodeResolver implements CodeResolver {

    private final JdbcClient jdbc;
    private final ProductRepository productRepository;
    private final ProductSnapshotMapper snapshotMapper;
    private final ScaleBarcodeConfigRepository scaleConfigRepository;

    public ScaleAwareCodeResolver(JdbcClient jdbc,
                                  ProductRepository productRepository,
                                  ProductSnapshotMapper snapshotMapper,
                                  ScaleBarcodeConfigRepository scaleConfigRepository) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper");
        this.scaleConfigRepository = Objects.requireNonNull(scaleConfigRepository, "scaleConfigRepository");
    }

    @Override
    public Optional<ProductCatalogApi.ScanResult> resolve(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String trimmed = code.trim();

        // 1. Código de barras regular (no SCALE_PLU)
        Optional<Long> byBarcode = jdbc.sql("""
                SELECT pb.product_id
                FROM product_barcodes pb
                JOIN products p ON p.id = pb.product_id
                WHERE pb.code = :code
                  AND pb.barcode_type <> 'SCALE_PLU'
                  AND p.active = true
                LIMIT 1
                """)
                .param("code", trimmed)
                .query(Long.class)
                .optional();
        if (byBarcode.isPresent()) {
            return findSnapshot(byBarcode.get())
                    .map(s -> new ProductCatalogApi.ScanResult(
                            s, null, null, ProductCatalogApi.ScanSource.BARCODE));
        }

        // 2. Código EAN-13 de balanza (valida dígito verificador y extrae PLU + peso/precio)
        Optional<ProductCatalogApi.ScanResult> scaleResult = resolveScale(trimmed);
        if (scaleResult.isPresent()) {
            return scaleResult;
        }

        // 3. Código de atajo
        Optional<Product> byShortcut = productRepository.findByShortcutCodeAndActiveTrue(trimmed);
        if (byShortcut.isPresent()) {
            return findSnapshot(byShortcut.get().getId())
                    .map(s -> new ProductCatalogApi.ScanResult(
                            s, null, null, ProductCatalogApi.ScanSource.SHORTCUT));
        }

        // 4. SKU
        Optional<Product> bySku = productRepository.findBySkuAndActiveTrue(trimmed);
        return bySku.flatMap(p -> findSnapshot(p.getId())
                .map(s -> new ProductCatalogApi.ScanResult(
                        s, null, null, ProductCatalogApi.ScanSource.SKU)));
    }

    private Optional<ProductCatalogApi.ScanResult> resolveScale(String code) {
        List<ScaleBarcodeConfig> activeConfigs = scaleConfigRepository.findAllByActiveTrueOrderByPrefixAsc();
        Optional<ScaleBarcodeDecoder.DecodedScaleBarcode> decodedOpt = ScaleBarcodeDecoder.decode(code, activeConfigs);
        if (decodedOpt.isEmpty()) {
            return Optional.empty();
        }

        ScaleBarcodeDecoder.DecodedScaleBarcode decoded = decodedOpt.get();
        Optional<Long> productId = jdbc.sql("""
                SELECT pb.product_id
                FROM product_barcodes pb
                JOIN products p ON p.id = pb.product_id
                WHERE pb.code = :plu
                  AND pb.barcode_type = 'SCALE_PLU'
                  AND p.active = true
                LIMIT 1
                """)
                .param("plu", decoded.plu())
                .query(Long.class)
                .optional();

        if (productId.isEmpty()) {
            return Optional.empty();
        }

        return findSnapshot(productId.get()).map(snapshot -> {
            BigDecimal quantity = ScaleBarcodeDecoder.resolveQuantity(decoded, snapshot.priceGross());
            BigDecimal embeddedPrice = ScaleBarcodeDecoder.resolveEmbeddedPrice(decoded);
            return new ProductCatalogApi.ScanResult(
                    snapshot,
                    quantity,
                    embeddedPrice,
                    ProductCatalogApi.ScanSource.SCALE);
        });
    }

    private Optional<ProductCatalogApi.ProductSnapshot> findSnapshot(Long productId) {
        return snapshotMapper.findSnapshotsByIds(List.of(productId)).stream().findFirst();
    }
}

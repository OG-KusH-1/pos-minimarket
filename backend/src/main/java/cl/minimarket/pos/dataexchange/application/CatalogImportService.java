package cl.minimarket.pos.dataexchange.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.PriceChangeType;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ImportProductCommand;
import cl.minimarket.pos.catalog.RestrictedType;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.dataexchange.domain.ImportJob;
import cl.minimarket.pos.dataexchange.domain.ImportJobRepository;
import cl.minimarket.pos.dataexchange.domain.ImportJobStatus;
import cl.minimarket.pos.dataexchange.domain.ImportRowError;
import cl.minimarket.pos.dataexchange.domain.ImportType;
import cl.minimarket.pos.dataexchange.infrastructure.CatalogLookupRepository;
import cl.minimarket.pos.dataexchange.infrastructure.CatalogLookupRepository.ProductRef;
import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileContent;
import cl.minimarket.pos.files.FileMetadata;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.purchasing.ProductSupplierApi.ProductSupplierCommand;
import cl.minimarket.pos.purchasing.ProductSupplierApi.SupplierRef;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreAccessValidator;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.export.CsvXlsxReader;
import cl.minimarket.pos.shared.export.CsvXlsxReader.ImportRow;
import cl.minimarket.pos.shared.export.InvalidImportFileException;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.rut.Ean;
import cl.minimarket.pos.shared.rut.Rut;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Orquesta la importación masiva del catálogo ({@code PRODUCTS}, {@code PRODUCT_SUPPLIERS},
 * {@code INITIAL_STOCK}): validación previa ({@code dryRun}) sin escrituras y confirmación
 * transaccional todo-o-nada ({@code HU-CAT-10}, {@code API.md} F.2).
 */
@Service
public class CatalogImportService {

    /** Límite máximo de archivo de importación: 20 MB (MODELO_DATOS B.1). */
    public static final long MAX_IMPORT_BYTES = 20L * 1024 * 1024;

    private static final String ACTION_DATA_IMPORTED = "DATA_IMPORTED";
    private static final String ENTITY_IMPORT_JOB = "import_job";
    private static final Pattern BARCODE_PATTERN = Pattern.compile("^[0-9A-Za-z]{3,20}$");
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");
    private static final int MAX_SKU_LENGTH = 40;
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_PURCHASE_UNIT_LENGTH = 30;
    private static final int MAX_LOT_NUMBER_LENGTH = 40;

    private final FileApi fileApi;
    private final ImportJobRepository importJobRepository;
    private final CatalogLookupRepository catalogLookup;
    private final ProductCatalogApi productCatalogApi;
    private final ProductSupplierApi productSupplierApi;
    private final StockApi stockApi;
    private final AuditService auditService;
    private final BusinessClock clock;
    private final ObjectProvider<StoreAccessValidator> storeAccessValidator;
    private final CsvXlsxReader reader = new CsvXlsxReader();

    public CatalogImportService(FileApi fileApi,
                                ImportJobRepository importJobRepository,
                                CatalogLookupRepository catalogLookup,
                                ProductCatalogApi productCatalogApi,
                                ProductSupplierApi productSupplierApi,
                                StockApi stockApi,
                                AuditService auditService,
                                BusinessClock clock,
                                ObjectProvider<StoreAccessValidator> storeAccessValidator) {
        this.fileApi = fileApi;
        this.importJobRepository = importJobRepository;
        this.catalogLookup = catalogLookup;
        this.productCatalogApi = productCatalogApi;
        this.productSupplierApi = productSupplierApi;
        this.stockApi = stockApi;
        this.auditService = auditService;
        this.clock = clock;
        this.storeAccessValidator = storeAccessValidator;
    }

    /**
     * Sube, registra y valida un archivo de importación; si {@code dryRun=false} y no hay errores,
     * aplica los cambios en una sola transacción.
     *
     * @param file       archivo CSV o XLSX (máximo 20 MB)
     * @param importType tipo de importación
     * @param dryRun     {@code true} para solo validar sin escribir en el catálogo/stock
     * @param storeId    local activo opcional (necesario si {@code dryRun=false} e {@code INITIAL_STOCK})
     * @return trabajo de importación registrado
     */
    @Transactional
    public ImportJob createImport(MultipartFile file, ImportType importType, boolean dryRun, Long storeId) {
        validateMultipartFile(file);

        FileMetadata metadata;
        try (InputStream in = file.getInputStream()) {
            metadata = fileApi.register(
                    in, file.getOriginalFilename(), file.getContentType(), file.getSize(), FileCategory.IMPORT);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "No se pudo leer el archivo subido.");
        }

        List<ImportRow> rows = readStoredRows(metadata.id(), metadata.originalName());
        ValidationPlan plan = validateRows(importType, rows);

        Long userId = CurrentUserId.find().orElse(null);
        Instant now = clock.now();

        if (dryRun) {
            return importJobRepository.insert(
                    importType,
                    metadata.id(),
                    ImportJobStatus.VALIDATED,
                    true,
                    plan.totalRows(),
                    plan.okRows(),
                    plan.errorRows(),
                    plan.errors(),
                    now,
                    userId,
                    now
            );
        }

        if (plan.errorRows() > 0) {
            throw new BusinessException(
                    ErrorCode.IMPORT_INVALID,
                    "El archivo de importación contiene " + plan.errorRows() + " fila(s) con errores.");
        }

        Long resolvedStoreId = resolveStoreForApply(importType, storeId, userId);
        ImportJob job = importJobRepository.insert(
                importType,
                metadata.id(),
                ImportJobStatus.PROCESSING,
                false,
                plan.totalRows(),
                plan.okRows(),
                0,
                List.of(),
                null,
                userId,
                now
        );

        applyPlan(job.id(), importType, plan, resolvedStoreId, userId);
        ImportJob completed = importJobRepository.markCompleted(job.id(), false, clock.now(), userId, clock.now());
        recordAudit(completed);
        return completed;
    }

    /**
     * Consulta un trabajo de importación por su identificador.
     */
    @Transactional(readOnly = true)
    public ImportJob getImport(Long id) {
        return importJobRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Importación no encontrada."));
    }

    /**
     * Confirma y aplica en una sola transacción un trabajo de importación previamente validado.
     *
     * @param id      identificador del trabajo {@code import_jobs}
     * @param storeId local activo (requerido para {@link ImportType#INITIAL_STOCK})
     * @return trabajo actualizado a estado {@link ImportJobStatus#COMPLETED}
     */
    @Transactional
    public ImportJob confirmImport(Long id, Long storeId) {
        ImportJob job = importJobRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Importación no encontrada."));

        if (job.status() != ImportJobStatus.VALIDATED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden confirmar importaciones en estado VALIDATED.");
        }
        if (job.errorRows() > 0 || (job.errors() != null && !job.errors().isEmpty())) {
            throw new BusinessException(ErrorCode.IMPORT_INVALID,
                    "No se puede confirmar una importación con errores de validación.");
        }

        Long userId = CurrentUserId.find().orElse(null);
        Long resolvedStoreId = resolveStoreForApply(job.importType(), storeId, userId);

        FileMetadata metadata = fileApi.get(job.fileId());
        List<ImportRow> rows = readStoredRows(job.fileId(), metadata.originalName());
        ValidationPlan plan = validateRows(job.importType(), rows);
        if (plan.errorRows() > 0) {
            throw new BusinessException(ErrorCode.IMPORT_INVALID,
                    "La importación ya no es válida: " + plan.errorRows() + " fila(s) con errores.");
        }

        applyPlan(job.id(), job.importType(), plan, resolvedStoreId, userId);
        Instant now = clock.now();
        ImportJob completed = importJobRepository.markCompleted(job.id(), false, now, userId, now);
        recordAudit(completed);
        return completed;
    }

    // ---- Lectura y validación de archivo ----

    private static void validateMultipartFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "El archivo está vacío.");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new BusinessException(ErrorCode.FILE_INVALID,
                    "El archivo excede el tamaño máximo permitido (20 MB).");
        }
    }

    private List<ImportRow> readStoredRows(Long fileId, String originalName) {
        FileContent content = fileApi.open(fileId);
        try (InputStream in = content.stream()) {
            return reader.readAll(in, originalName);
        } catch (InvalidImportFileException e) {
            throw new BusinessException(ErrorCode.FILE_INVALID, e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "No se pudo leer el archivo almacenado.");
        }
    }

    private Long resolveStoreForApply(ImportType importType, Long storeIdHeader, Long userId) {
        if (importType != ImportType.INITIAL_STOCK) {
            return null;
        }
        Long storeId = StoreContext.current()
                .map(StoreContext::storeId)
                .orElse(storeIdHeader);
        if (storeId == null || storeId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Se requiere indicar el local activo (X-Store-Id) para importar stock inicial.");
        }
        StoreAccessValidator validator = storeAccessValidator.getIfAvailable();
        if (validator != null && userId != null) {
            validator.requireAccess(userId, storeId, null);
        }
        return storeId;
    }

    // ---- Validación por tipo de importación ----

    private ValidationPlan validateRows(ImportType importType, List<ImportRow> rows) {
        if (rows.isEmpty()) {
            return new ValidationPlan(0, 0, 0, List.of(), List.of(), List.of(), List.of());
        }
        validateRequiredHeaders(importType, rows.getFirst().values().keySet());

        return switch (importType) {
            case PRODUCTS -> validateProducts(rows);
            case PRODUCT_SUPPLIERS -> validateProductSuppliers(rows);
            case INITIAL_STOCK -> validateInitialStock(rows);
        };
    }

    private static void validateRequiredHeaders(ImportType importType, Set<String> presentHeaders) {
        List<String> required = switch (importType) {
            case PRODUCTS -> List.of("sku", "name", "category", "saleType", "priceGross");
            case PRODUCT_SUPPLIERS -> List.of("sku", "supplierRut", "purchaseUnitName", "units", "cost", "moq");
            case INITIAL_STOCK -> List.of("sku", "quantity");
        };
        for (String req : required) {
            if (!presentHeaders.contains(req)) {
                throw new BusinessException(ErrorCode.IMPORT_INVALID,
                        "Falta la columna obligatoria '" + req + "' para la importación " + importType + ".");
            }
        }
    }

    private ValidationPlan validateProducts(List<ImportRow> rows) {
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportProductCommand> commands = new ArrayList<>();
        Set<Integer> invalidRows = new HashSet<>();
        Set<String> seenSkus = new HashSet<>();
        Set<String> seenBarcodes = new HashSet<>();

        for (ImportRow row : rows) {
            int rowNum = row.rowNumber();
            Map<String, String> v = row.values();
            boolean rowValid = true;

            String sku = trim(v.get("sku"));
            if (sku.isEmpty()) {
                rowValid = addError(errors, invalidRows, rowNum, "sku", "El SKU es obligatorio.");
            } else if (sku.length() > MAX_SKU_LENGTH) {
                rowValid = addError(errors, invalidRows, rowNum, "sku",
                        "El SKU supera los " + MAX_SKU_LENGTH + " caracteres.");
            } else if (!seenSkus.add(sku)) {
                rowValid = addError(errors, invalidRows, rowNum, "sku",
                        "SKU duplicado dentro del archivo: " + sku);
            } else if (catalogLookup.existsProductBySku(sku)) {
                rowValid = addError(errors, invalidRows, rowNum, "sku",
                        "Ya existe un producto con el SKU '" + sku + "'.");
            }

            String name = trim(v.get("name"));
            if (name.isEmpty()) {
                rowValid = addError(errors, invalidRows, rowNum, "name", "El nombre es obligatorio.");
            } else if (name.length() > MAX_NAME_LENGTH) {
                rowValid = addError(errors, invalidRows, rowNum, "name",
                        "El nombre supera los " + MAX_NAME_LENGTH + " caracteres.");
            }

            String categoryName = trim(v.get("category"));
            String subcategoryName = trim(v.get("subcategory"));
            Long resolvedCategoryId = null;
            if (categoryName.isEmpty()) {
                rowValid = addError(errors, invalidRows, rowNum, "category", "La categoría es obligatoria.");
            } else {
                Optional<Long> rootId = catalogLookup.findRootCategoryIdByName(categoryName);
                if (rootId.isEmpty()) {
                    rowValid = addError(errors, invalidRows, rowNum, "category",
                            "Categoría no encontrada o inactiva: " + categoryName);
                } else if (!subcategoryName.isEmpty()) {
                    Optional<Long> subId = catalogLookup.findSubcategoryIdByName(rootId.get(), subcategoryName);
                    if (subId.isEmpty()) {
                        rowValid = addError(errors, invalidRows, rowNum, "subcategory",
                                "Subcategoría '" + subcategoryName + "' no encontrada bajo '" + categoryName + "'.");
                    } else {
                        resolvedCategoryId = subId.get();
                    }
                } else {
                    resolvedCategoryId = rootId.get();
                }
            }

            String brandName = trim(v.get("brand"));
            Long resolvedBrandId = null;
            if (!brandName.isEmpty()) {
                Optional<Long> brandId = catalogLookup.findBrandIdByName(brandName);
                if (brandId.isEmpty()) {
                    rowValid = addError(errors, invalidRows, rowNum, "brand",
                            "Marca no encontrada o inactiva: " + brandName);
                } else {
                    resolvedBrandId = brandId.get();
                }
            }

            String saleTypeRaw = trim(v.get("saleType")).toUpperCase(Locale.ROOT);
            SaleType saleType = null;
            if (saleTypeRaw.isEmpty()) {
                rowValid = addError(errors, invalidRows, rowNum, "saleType", "El tipo de venta es obligatorio.");
            } else {
                try {
                    saleType = SaleType.valueOf(saleTypeRaw);
                    if (saleType == SaleType.PACK) {
                        rowValid = addError(errors, invalidRows, rowNum, "saleType",
                                "No se admiten productos PACK en la importación masiva.");
                    }
                } catch (IllegalArgumentException e) {
                    rowValid = addError(errors, invalidRows, rowNum, "saleType",
                            "Tipo de venta inválido: " + saleTypeRaw + " (use UNIT o WEIGHT).");
                }
            }

            String priceRaw = trim(v.get("priceGross"));
            BigDecimal priceGross = null;
            if (priceRaw.isEmpty()) {
                rowValid = addError(errors, invalidRows, rowNum, "priceGross", "El precio bruto es obligatorio.");
            } else {
                try {
                    BigDecimal parsed = new BigDecimal(priceRaw);
                    if (parsed.signum() < 0 || parsed.stripTrailingZeros().scale() > 0) {
                        rowValid = addError(errors, invalidRows, rowNum, "priceGross",
                                "El precio bruto debe ser un entero mayor o igual a 0.");
                    } else {
                        priceGross = Rounding.clp(parsed);
                    }
                } catch (NumberFormatException e) {
                    rowValid = addError(errors, invalidRows, rowNum, "priceGross",
                            "Precio bruto inválido: " + priceRaw);
                }
            }

            List<String> barcodes = splitPipe(v.get("barcodes"));
            for (String code : barcodes) {
                if (!isValidBarcode(code)) {
                    rowValid = addError(errors, invalidRows, rowNum, "barcodes",
                            "Código de barras inválido: " + code);
                } else if (!seenBarcodes.add(code)) {
                    rowValid = addError(errors, invalidRows, rowNum, "barcodes",
                            "Código de barras duplicado en el archivo: " + code);
                } else if (catalogLookup.existsBarcode(code)) {
                    rowValid = addError(errors, invalidRows, rowNum, "barcodes",
                            "Código de barras ya registrado en otro producto: " + code);
                }
            }

            List<String> taxCodes = splitPipe(v.get("taxes"));
            for (String taxCode : taxCodes) {
                if (!catalogLookup.existsActiveTaxByCode(taxCode)) {
                    rowValid = addError(errors, invalidRows, rowNum, "taxes",
                            "Impuesto no encontrado o inactivo: " + taxCode);
                }
            }

            String restrictedRaw = trim(v.get("restricted")).toUpperCase(Locale.ROOT);
            RestrictedType restrictedType = null;
            if (!restrictedRaw.isEmpty() && !"NONE".equals(restrictedRaw) && !"NO".equals(restrictedRaw)) {
                try {
                    restrictedType = RestrictedType.valueOf(restrictedRaw);
                } catch (IllegalArgumentException e) {
                    rowValid = addError(errors, invalidRows, rowNum, "restricted",
                            "Tipo de restricción inválido: " + restrictedRaw + " (ALCOHOL, TOBACCO, OTHER).");
                }
            }

            Boolean lotControlled = parseBoolean(trim(v.get("lotControlled")));
            if (lotControlled == null) {
                rowValid = addError(errors, invalidRows, rowNum, "lotControlled",
                        "Valor booleano inválido para lotControlled.");
                lotControlled = false;
            }

            if (rowValid) {
                commands.add(new ImportProductCommand(
                        sku,
                        name,
                        resolvedCategoryId,
                        resolvedBrandId,
                        saleType,
                        priceGross,
                        barcodes,
                        taxCodes,
                        restrictedType,
                        lotControlled
                ));
            }
        }

        int total = rows.size();
        int errorCount = invalidRows.size();
        return new ValidationPlan(total, total - errorCount, errorCount, errors, commands, List.of(), List.of());
    }

    private ValidationPlan validateProductSuppliers(List<ImportRow> rows) {
        List<ImportRowError> errors = new ArrayList<>();
        List<ProductSupplierCommand> commands = new ArrayList<>();
        Set<Integer> invalidRows = new HashSet<>();
        Map<String, ProductRef> productCache = new HashMap<>();
        Map<String, Optional<SupplierRef>> supplierCache = new HashMap<>();

        for (ImportRow row : rows) {
            int rowNum = row.rowNumber();
            Map<String, String> v = row.values();
            boolean rowValid = true;

            String sku = trim(v.get("sku"));
            ProductRef product = null;
            if (sku.isEmpty()) {
                rowValid = addError(errors, invalidRows, rowNum, "sku", "El SKU es obligatorio.");
            } else {
                product = productCache.computeIfAbsent(sku, k -> catalogLookup.findProductBySku(k).orElse(null));
                if (product == null) {
                    rowValid = addError(errors, invalidRows, rowNum, "sku", "Producto no encontrado: " + sku);
                } else if (product.saleType() == SaleType.PACK) {
                    rowValid = addError(errors, invalidRows, rowNum, "sku",
                            "Un producto PACK no puede asociarse a un proveedor.");
                }
            }

            String rutRaw = trim(v.get("supplierRut"));
            SupplierRef supplier = null;
            if (rutRaw.isEmpty()) {
                rowValid = addError(errors, invalidRows, rowNum, "supplierRut", "El RUT del proveedor es obligatorio.");
            } else if (!Rut.isValid(rutRaw)) {
                rowValid = addError(errors, invalidRows, rowNum, "supplierRut", "RUT inválido: " + rutRaw);
            } else {
                String canonicalRut = Rut.of(rutRaw).normalized();
                Optional<SupplierRef> found = supplierCache.computeIfAbsent(
                        canonicalRut, productSupplierApi::findSupplierByRut);
                if (found.isEmpty() || !found.get().active()) {
                    rowValid = addError(errors, invalidRows, rowNum, "supplierRut",
                            "Proveedor no encontrado o inactivo para RUT: " + rutRaw);
                } else {
                    supplier = found.get();
                }
            }

            String unitName = trim(v.get("purchaseUnitName"));
            if (unitName.isEmpty() || unitName.length() > MAX_PURCHASE_UNIT_LENGTH) {
                rowValid = addError(errors, invalidRows, rowNum, "purchaseUnitName",
                        "La unidad de compra es obligatoria (máximo " + MAX_PURCHASE_UNIT_LENGTH + " caracteres).");
            }

            BigDecimal units = parsePositiveDecimal(trim(v.get("units")));
            if (units == null) {
                rowValid = addError(errors, invalidRows, rowNum, "units",
                        "Las unidades por unidad de compra deben ser mayores a 0.");
            }

            BigDecimal cost = parseNonNegativeDecimal(trim(v.get("cost")));
            if (cost == null) {
                rowValid = addError(errors, invalidRows, rowNum, "cost",
                        "El costo debe ser un número mayor o igual a 0.");
            }

            BigDecimal moq = parseMinOneDecimal(trim(v.get("moq")));
            if (moq == null) {
                rowValid = addError(errors, invalidRows, rowNum, "moq",
                        "La cantidad mínima de pedido (moq) debe ser mayor o igual a 1.");
            }

            if (rowValid) {
                commands.add(new ProductSupplierCommand(
                        product.id(),
                        supplier.id(),
                        null,
                        unitName,
                        units,
                        cost,
                        moq,
                        null,
                        null
                ));
            }
        }

        int total = rows.size();
        int errorCount = invalidRows.size();
        return new ValidationPlan(total, total - errorCount, errorCount, errors, List.of(), commands, List.of());
    }

    private ValidationPlan validateInitialStock(List<ImportRow> rows) {
        List<ImportRowError> errors = new ArrayList<>();
        List<InitialStockItem> items = new ArrayList<>();
        Set<Integer> invalidRows = new HashSet<>();
        Map<String, ProductRef> productCache = new HashMap<>();

        for (ImportRow row : rows) {
            int rowNum = row.rowNumber();
            Map<String, String> v = row.values();
            boolean rowValid = true;

            String sku = trim(v.get("sku"));
            ProductRef product = null;
            if (sku.isEmpty()) {
                rowValid = addError(errors, invalidRows, rowNum, "sku", "El SKU es obligatorio.");
            } else {
                product = productCache.computeIfAbsent(sku, k -> catalogLookup.findProductBySku(k).orElse(null));
                if (product == null) {
                    rowValid = addError(errors, invalidRows, rowNum, "sku", "Producto no encontrado: " + sku);
                } else if (product.saleType() == SaleType.PACK) {
                    rowValid = addError(errors, invalidRows, rowNum, "sku",
                            "Los productos PACK no tienen stock propio.");
                } else if (!product.active()) {
                    rowValid = addError(errors, invalidRows, rowNum, "sku",
                            "El producto está inactivo: " + sku);
                }
            }

            BigDecimal quantity = parsePositiveDecimal(trim(v.get("quantity")));
            if (quantity == null) {
                rowValid = addError(errors, invalidRows, rowNum, "quantity",
                        "La cantidad debe ser un número mayor a 0.");
            }

            String costRaw = trim(v.get("unitCost"));
            BigDecimal unitCost = null;
            if (!costRaw.isEmpty()) {
                unitCost = parseNonNegativeDecimal(costRaw);
                if (unitCost == null) {
                    rowValid = addError(errors, invalidRows, rowNum, "unitCost",
                            "El costo unitario debe ser un número mayor o igual a 0.");
                }
            }

            String lotNumber = trim(v.get("lotNumber"));
            String expiryRaw = trim(v.get("expiryDate"));
            LocalDate expiryDate = null;
            if (!expiryRaw.isEmpty()) {
                try {
                    expiryDate = LocalDate.parse(expiryRaw);
                } catch (DateTimeParseException e) {
                    rowValid = addError(errors, invalidRows, rowNum, "expiryDate",
                            "Fecha de vencimiento inválida (use yyyy-MM-dd): " + expiryRaw);
                }
            }

            if (product != null) {
                if (product.lotControlled()) {
                    if (lotNumber.isEmpty() || lotNumber.length() > MAX_LOT_NUMBER_LENGTH) {
                        rowValid = addError(errors, invalidRows, rowNum, "lotNumber",
                                "El producto tiene control por lote: lotNumber es obligatorio (máx. 40 caracteres).");
                    }
                } else {
                    if (!lotNumber.isEmpty()) {
                        rowValid = addError(errors, invalidRows, rowNum, "lotNumber",
                                "El producto no tiene control por lote; deje lotNumber vacío.");
                    }
                    if (expiryDate != null) {
                        rowValid = addError(errors, invalidRows, rowNum, "expiryDate",
                                "El producto no tiene control por lote; deje expiryDate vacío.");
                    }
                }
            }

            if (rowValid) {
                items.add(new InitialStockItem(
                        product.id(),
                        quantity,
                        unitCost,
                        lotNumber.isEmpty() ? null : lotNumber,
                        expiryDate
                ));
            }
        }

        int total = rows.size();
        int errorCount = invalidRows.size();
        return new ValidationPlan(total, total - errorCount, errorCount, errors, List.of(), List.of(), items);
    }

    // ---- Aplicación transaccional ----

    private void applyPlan(Long jobId, ImportType importType, ValidationPlan plan, Long storeId, Long userId) {
        switch (importType) {
            case PRODUCTS -> {
                for (ImportProductCommand cmd : plan.productCommands()) {
                    productCatalogApi.createFromImport(cmd);
                }
            }
            case PRODUCT_SUPPLIERS -> {
                for (ProductSupplierCommand cmd : plan.supplierCommands()) {
                    productSupplierApi.upsert(cmd);
                }
            }
            case INITIAL_STOCK -> {
                if (plan.initialStockItems().isEmpty()) {
                    return;
                }
                List<StockLine> lines = plan.initialStockItems().stream()
                        .map(item -> new StockLine(
                                item.productId(),
                                item.quantity(),
                                item.unitCost(),
                                null,
                                null,
                                item.lotNumber(),
                                item.expiryDate()))
                        .toList();
                Long effectiveUserId = userId != null ? userId : 0L;
                stockApi.apply(new StockCommand(
                        storeId,
                        StockMovementType.INITIAL,
                        StockSourceType.INITIAL_LOAD,
                        jobId,
                        effectiveUserId,
                        "Carga inicial #" + jobId,
                        lines
                ));
                for (InitialStockItem item : plan.initialStockItems()) {
                    if (item.unitCost() != null) {
                        productCatalogApi.applyAverageCost(
                                item.productId(),
                                item.unitCost(),
                                PriceChangeType.COST_INITIAL,
                                "INITIAL_LOAD:" + jobId
                        );
                    }
                }
            }
        }
    }

    private void recordAudit(ImportJob job) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("importType", job.importType().name());
        payload.put("fileId", job.fileId());
        payload.put("totalRows", job.totalRows());
        payload.put("okRows", job.okRows());
        auditService.record(new AuditEvent(
                ACTION_DATA_IMPORTED,
                ENTITY_IMPORT_JOB,
                job.id().toString(),
                null,
                payload,
                null,
                null
        ));
    }

    // ---- Utilidades ----

    private static boolean addError(List<ImportRowError> errors, Set<Integer> invalidRows,
                                    int row, String field, String message) {
        invalidRows.add(row);
        errors.add(new ImportRowError(row, field, message));
        return false;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static List<String> splitPipe(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("\\|");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static boolean isValidBarcode(String code) {
        if (!BARCODE_PATTERN.matcher(code).matches()) {
            return false;
        }
        if (DIGITS_ONLY.matcher(code).matches()) {
            if (code.length() == 13 || code.length() == 8) {
                return Ean.isValid(code);
            }
            if (code.length() == 12) {
                return Ean.isValid("0" + code);
            }
        }
        return true;
    }

    private static Boolean parseBoolean(String raw) {
        if (raw.isEmpty()) {
            return Boolean.FALSE;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "1", "si", "sí", "yes", "s" -> Boolean.TRUE;
            case "false", "0", "no", "n" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static BigDecimal parsePositiveDecimal(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseNonNegativeDecimal(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.signum() >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseMinOneDecimal(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.compareTo(BigDecimal.ONE) >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record InitialStockItem(
            Long productId,
            BigDecimal quantity,
            BigDecimal unitCost,
            String lotNumber,
            LocalDate expiryDate
    ) {
    }

    private record ValidationPlan(
            int totalRows,
            int okRows,
            int errorRows,
            List<ImportRowError> errors,
            List<ImportProductCommand> productCommands,
            List<ProductSupplierCommand> supplierCommands,
            List<InitialStockItem> initialStockItems
    ) {
    }
}

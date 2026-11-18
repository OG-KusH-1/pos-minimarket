package cl.minimarket.pos.catalog.scale.application;

import cl.minimarket.pos.catalog.scale.domain.EmbeddedType;
import cl.minimarket.pos.catalog.scale.domain.ScaleBarcodeConfig;
import cl.minimarket.pos.catalog.scale.domain.ScaleBarcodeConfigRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Casos de uso de configuraciones de códigos de barras de balanza ({@code scale_barcode_configs},
 * {@code HU-CFG-07}).
 */
@Service
public class ScaleBarcodeConfigService {

    private static final int EAN_13_LENGTH = 13;

    private final ScaleBarcodeConfigRepository configRepository;

    public ScaleBarcodeConfigService(ScaleBarcodeConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /** Comando de creación o actualización de una configuración de balanza. */
    public record ScaleConfigCommand(
            String name,
            String prefix,
            EmbeddedType embeddedType,
            short pluStart,
            short pluLength,
            short valueStart,
            short valueLength,
            short valueDecimals
    ) {
    }

    /** Lista todas las configuraciones de balanza ordenadas por identificador. */
    @Transactional(readOnly = true)
    public List<ScaleBarcodeConfig> list() {
        return configRepository.findAllByOrderByIdAsc();
    }

    /**
     * Crea una nueva configuración de balanza validando rangos EAN-13 y unicidad de prefijo.
     */
    @Transactional
    public ScaleBarcodeConfig create(ScaleConfigCommand cmd) {
        String prefix = cmd.prefix().trim();
        validateRanges(cmd);
        if (configRepository.existsByPrefix(prefix)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe una configuración de balanza con el prefijo '" + prefix + "'.");
        }

        ScaleBarcodeConfig config = new ScaleBarcodeConfig();
        applyFields(config, cmd, prefix);
        config.setActive(true);
        return configRepository.save(config);
    }

    /**
     * Actualiza una configuración de balanza existente validando rangos EAN-13 y unicidad de prefijo.
     */
    @Transactional
    public ScaleBarcodeConfig update(Long id, ScaleConfigCommand cmd) {
        ScaleBarcodeConfig config = configRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Configuración de balanza no encontrada."));
        String prefix = cmd.prefix().trim();
        validateRanges(cmd);
        if (configRepository.existsByPrefixAndIdNot(prefix, id)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe una configuración de balanza con el prefijo '" + prefix + "'.");
        }

        applyFields(config, cmd, prefix);
        return configRepository.save(config);
    }

    private static void validateRanges(ScaleConfigCommand cmd) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (cmd.pluStart() + cmd.pluLength() > EAN_13_LENGTH) {
            errors.add(new FieldErrorDetail(
                    "pluLength",
                    "RANGE_EXCEEDED",
                    "pluStart + pluLength no puede superar 13 posiciones."));
        }
        if (cmd.valueStart() + cmd.valueLength() > EAN_13_LENGTH) {
            errors.add(new FieldErrorDetail(
                    "valueLength",
                    "RANGE_EXCEEDED",
                    "valueStart + valueLength no puede superar 13 posiciones."));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Las posiciones de PLU o valor exceden el largo EAN-13 (13 dígitos).",
                    Map.of(),
                    errors);
        }
    }

    private static void applyFields(ScaleBarcodeConfig config, ScaleConfigCommand cmd, String prefix) {
        config.setName(cmd.name().trim());
        config.setPrefix(prefix);
        config.setEmbeddedType(cmd.embeddedType());
        config.setPluStart(cmd.pluStart());
        config.setPluLength(cmd.pluLength());
        config.setValueStart(cmd.valueStart());
        config.setValueLength(cmd.valueLength());
        config.setValueDecimals(cmd.valueDecimals());
    }
}

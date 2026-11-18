package cl.minimarket.pos.shared.sequence;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceTypeTest {

    /** Debe coincidir con el CHECK ck_document_sequences_type de la migración 71. */
    @Test
    void namesMatchTheDatabaseCheckConstraint() {
        Set<String> names = Arrays.stream(SequenceType.values()).map(Enum::name).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("SALE", "SALE_RETURN", "PURCHASE_ORDER", "GOODS_RECEIPT",
                "TRANSFER", "INVENTORY_COUNT", "STOCK_ADJUSTMENT", "SHRINKAGE", "CASH_SESSION", "FOLIO_BOLETA",
                "FOLIO_CREDIT_NOTE");
    }

    @Test
    void onlyTaxFoliosAreGlobal() {
        Set<SequenceType> global = Arrays.stream(SequenceType.values())
                .filter(t -> t.scope() == SequenceType.Scope.GLOBAL).collect(Collectors.toSet());
        assertThat(global).containsExactlyInAnyOrder(SequenceType.FOLIO_BOLETA, SequenceType.FOLIO_CREDIT_NOTE);
    }

    @Test
    void codesAreUniqueAndShort() {
        Set<String> codes = Arrays.stream(SequenceType.values()).map(SequenceType::code).collect(Collectors.toSet());
        assertThat(codes).hasSize(SequenceType.values().length);
        assertThat(codes).allMatch(c -> c.length() <= 3);
    }

    @Test
    void formatPadsToEightDigitsAndGrowsBeyond() {
        assertThat(SequenceService.format("L01-V", 1234)).isEqualTo("L01-V-00001234");
        assertThat(SequenceService.format("NC", 123_456_789L)).isEqualTo("NC-123456789");
    }
}

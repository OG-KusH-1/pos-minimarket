package cl.minimarket.pos.shared.web;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSupportTest {

    private static final SortParser SORT = SortParser.allowing("name", "sku").withDefault(Sort.by("name"));

    @Test
    void pageQueryDefaults() {
        PageQuery query = PageQuery.of(null, null);

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
    }

    @Test
    void pageQueryAcceptsLimits() {
        assertThat(PageQuery.of(0, 1).size()).isEqualTo(1);
        assertThat(PageQuery.of(5, 200).size()).isEqualTo(200);
    }

    @Test
    void pageQueryRejectsOutOfRangeValues() {
        for (Integer[] invalid : new Integer[][]{{-1, 20}, {0, 0}, {0, 201}, {0, -5}}) {
            assertThatThrownBy(() -> PageQuery.of(invalid[0], invalid[1]))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
    }

    @Test
    void pageQueryBuildsPageable() {
        assertThat(PageQuery.of(2, 50).toPageable(Sort.by("name")))
                .isEqualTo(PageRequest.of(2, 50, Sort.by("name")));
    }

    @Test
    void sortParserUsesDefaultWhenNothingIsSent() {
        assertThat(SORT.parse(null)).isEqualTo(Sort.by("name"));
        assertThat(SORT.parse(List.of())).isEqualTo(Sort.by("name"));
    }

    @Test
    void sortParserParsesRepeatedCriteriaWithDirection() {
        Sort sort = SORT.parse(List.of("sku,DESC", "name"));

        assertThat(sort).containsExactly(Sort.Order.desc("sku"), Sort.Order.asc("name"));
    }

    @Test
    void sortParserMapsAliasesToProperties() {
        SortParser parser = SortParser.of(Map.of("createdAt", "created_at"));

        assertThat(parser.parse(List.of("createdAt,desc"))).containsExactly(Sort.Order.desc("created_at"));
    }

    @Test
    void sortParserKeepsFirstOccurrenceOfARepeatedField() {
        assertThat(SORT.parse(List.of("name,desc", "name,asc"))).containsExactly(Sort.Order.desc("name"));
    }

    @Test
    void sortParserRejectsFieldOutsideWhitelist() {
        assertThatThrownBy(() -> SORT.parse(List.of("costPrice,desc")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getErrors()).singleElement().satisfies(error -> {
                        assertThat(error.field()).isEqualTo("sort");
                        assertThat(error.code()).isEqualTo(SortParser.FIELD_NOT_ALLOWED);
                        assertThat(error.message()).contains("costPrice").contains("name, sku");
                    });
                });
    }

    @Test
    void sortParserRejectsInvalidDirectionAndFormat() {
        for (String raw : new String[]{"name,sideways", "name,asc,extra", "", " ", ",asc"}) {
            assertThatThrownBy(() -> SORT.parse(List.of(raw)))
                    .as("sort=%s", raw)
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
    }

    @Test
    void sortParserDoesNotReflectControlCharacters() {
        assertThatThrownBy(() -> SORT.parse(List.of("<script>alert(1)</script>")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrors().get(0).message()).doesNotContain("<script>"));
    }

    @Test
    void pageResponseMapsContentAndDescribesSort() {
        var page = new PageImpl<>(List.of(1, 2), PageRequest.of(1, 2, Sort.by(Sort.Order.desc("name"))), 5);

        PageResponse<String> response = PageResponse.from(page, n -> "n" + n);

        assertThat(response.content()).containsExactly("n1", "n2");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.sort()).containsExactly("name,desc");
        assertThat(PageResponse.of(new PageImpl<>(List.of("a"))).content()).containsExactly("a");
    }
}

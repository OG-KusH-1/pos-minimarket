package cl.minimarket.pos;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Prohíbe {@code double} y {@code float} (primitivos y envoltorios) en el código de producción
 * (regla A.5 "Dinero y redondeo" y H.1 n.º 6): todo monto, costo, cantidad o tasa es
 * {@link java.math.BigDecimal}.
 *
 * <p>Alcance: campos, parámetros y tipos de retorno de métodos y constructores de las clases de
 * {@code cl.minimarket.pos} (las pruebas se excluyen). Los componentes de un {@code record} se
 * detectan porque generan campo y accesor.
 *
 * <p>Excepciones: ninguna por ahora. Si fuera imprescindible una (p. ej. una API externa que
 * exige {@code double}), agregar el nombre completo de la clase a {@link #EXEMPT_CLASSES} con un
 * comentario que explique el motivo; la excepción es por clase, nunca por paquete.
 *
 * <p>Limitación conocida: no detecta variables locales ni conversiones internas; solo la API
 * (firma y estado) de cada clase, que es donde se filtran los montos.
 */
@AnalyzeClasses(packages = "cl.minimarket.pos", importOptions = ImportOption.DoNotIncludeTests.class)
class NoFloatingPointMoneyTest {

    /** Clases autorizadas a usar punto flotante. Debe permanecer vacío salvo justificación escrita. */
    private static final Set<String> EXEMPT_CLASSES = Set.of();

    private static final Set<String> FLOATING_POINT_TYPES = Set.of(
            "double", "float", "java.lang.Double", "java.lang.Float");

    @ArchTest
    static final ArchRule noFloatingPointInFieldsParametersOrReturns = classes()
            .that(DescribedPredicate.describe(
                    "no están en la lista de excepciones",
                    (JavaClass c) -> !EXEMPT_CLASSES.contains(c.getName())))
            .should(notUseFloatingPoint())
            .allowEmptyShould(true)
            .because("el dinero y las cantidades se manejan con BigDecimal (A.5 del plan)");

    private static ArchCondition<JavaClass> notUseFloatingPoint() {
        return new ArchCondition<>("no declarar campos, parámetros ni retornos double/float") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getFields()) {
                    if (isFloatingPoint(field.getRawType())) {
                        events.add(SimpleConditionEvent.violated(field,
                                "El campo " + field.getFullName() + " es de tipo " + field.getRawType().getName()));
                    }
                }
                for (JavaCodeUnit unit : javaClass.getCodeUnits()) {
                    if (isFloatingPoint(unit.getRawReturnType())) {
                        events.add(SimpleConditionEvent.violated(unit,
                                unit.getFullName() + " retorna " + unit.getRawReturnType().getName()));
                    }
                    for (JavaClass parameter : unit.getRawParameterTypes()) {
                        if (isFloatingPoint(parameter)) {
                            events.add(SimpleConditionEvent.violated(unit,
                                    unit.getFullName() + " recibe un parámetro " + parameter.getName()));
                        }
                    }
                }
            }
        };
    }

    private static boolean isFloatingPoint(JavaClass type) {
        return FLOATING_POINT_TYPES.contains(type.getName());
    }
}

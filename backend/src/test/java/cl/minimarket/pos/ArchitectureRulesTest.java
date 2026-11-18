package cl.minimarket.pos;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Reglas de arquitectura complementarias a {@link ModularityTest} (ADR-04 y A.5 del plan).
 *
 * <p>Todas usan {@code allowEmptyShould(true)} porque en la Fase 0 aún no existen controladores
 * ni otros módulos con código; las reglas cobran efecto en cuanto aparezcan clases.
 */
@AnalyzeClasses(packages = "cl.minimarket.pos", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /** A.5 "Transacciones": {@code @Transactional} solo en {@code application}, nunca en controllers. */
    @ArchTest
    static final ArchRule controllersAreNotTransactional = noClasses()
            .that().resideInAPackage("..web..")
            .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .orShould().beAnnotatedWith("jakarta.transaction.Transactional")
            .allowEmptyShould(true)
            .because("las transacciones se abren en la capa application, nunca en web");

    /** Variante a nivel de método de {@link #controllersAreNotTransactional}. */
    @ArchTest
    static final ArchRule controllerMethodsAreNotTransactional = noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage("..web..")
            .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .orShould().beAnnotatedWith("jakarta.transaction.Transactional")
            .allowEmptyShould(true)
            .because("las transacciones se abren en la capa application, nunca en web");

    /**
     * {@code shared} es el kernel base: no puede depender de ningún otro módulo. Modulith no lo
     * expresa con {@code allowedDependencies} vacío (significa sin restricción), por eso la regla.
     */
    @ArchTest
    static final ArchRule sharedDoesNotDependOnOtherModules = noClasses()
            .that().resideInAPackage("cl.minimarket.pos.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cl.minimarket.pos.audit..", "cl.minimarket.pos.security..",
                    "cl.minimarket.pos.organization..", "cl.minimarket.pos.files..",
                    "cl.minimarket.pos.notification..", "cl.minimarket.pos.catalog..",
                    "cl.minimarket.pos.promotions..", "cl.minimarket.pos.customers..",
                    "cl.minimarket.pos.inventory..", "cl.minimarket.pos.shrinkage..",
                    "cl.minimarket.pos.purchasing..", "cl.minimarket.pos.replenishment..",
                    "cl.minimarket.pos.sales..", "cl.minimarket.pos.billing..",
                    "cl.minimarket.pos.cash..", "cl.minimarket.pos.reporting..",
                    "cl.minimarket.pos.dataexchange..")
            .allowEmptyShould(true)
            .because("shared es el kernel base y no depende de ningún otro módulo (A.4 del plan)");
}

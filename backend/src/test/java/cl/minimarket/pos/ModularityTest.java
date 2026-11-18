package cl.minimarket.pos;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifica la estructura modular del monolito (ADR-02, regla H.1 n.º 6).
 *
 * <p>No levanta el contexto de Spring: Modulith analiza el bytecode con ArchUnit, de modo que
 * esta prueba corre sin base de datos y debe mantenerse así (no agregar {@code @SpringBootTest}).
 *
 * <p>Qué falla aquí: ciclos entre módulos, dependencias no declaradas en
 * {@code allowedDependencies} y accesos a paquetes internos (cualquier subpaquete de un módulo
 * que no sea su paquete raíz) desde otros módulos.
 */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(PosApplication.class);

    /** Falla ante cualquier violación de las reglas de dependencia entre módulos. */
    @Test
    void verifiesModularStructure() {
        MODULES.verify();
    }

    /**
     * Genera la documentación de módulos (diagramas PlantUML) en
     * {@code target/spring-modulith-docs}. Es solo un artefacto de build: no se versiona.
     */
    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}

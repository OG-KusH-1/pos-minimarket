package cl.minimarket.pos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada del backend de POS Minimarket (monolito modular).
 *
 * <p>Cada subpaquete directo de {@code cl.minimarket.pos} es un módulo de Spring Modulith
 * (ver el {@code package-info.java} de cada uno y la tabla A.4 de {@code docs/PLAN.md}).
 * Las fronteras entre módulos las verifica {@code ModularityTest}.
 *
 * <p>Decisión de diseño: esta clase vive en el paquete raíz para que el escaneo de componentes
 * y de entidades cubra todos los módulos sin configuración adicional. No debe contener lógica
 * ni beans: la configuración transversal pertenece a {@code shared} o al módulo que la necesita.
 */
@SpringBootApplication
public class PosApplication {

    /**
     * Arranca la aplicación. El perfil activo se elige con {@code SPRING_PROFILES_ACTIVE}
     * (dev, demo, test o prod).
     *
     * @param args argumentos de línea de comandos
     */
    public static void main(String[] args) {
        SpringApplication.run(PosApplication.class, args);
    }
}

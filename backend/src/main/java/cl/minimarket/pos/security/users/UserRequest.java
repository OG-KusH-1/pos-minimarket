package cl.minimarket.pos.security.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Solicitud de creación o actualización de usuario ({@code API.md} F.3).
 *
 * @param username nombre de usuario único ({@code ^[a-z0-9._-]{3,50}$})
 * @param fullName nombre completo (2..150)
 * @param email correo electrónico opcional
 * @param rut RUT chileno opcional (se valida por módulo 11 y se normaliza)
 * @param roleIds identificadores de roles asignados (al menos 1)
 * @param storeIds identificadores de locales asignados (al menos 1 salvo rol con {@code security:store:all-access})
 * @param defaultStoreId identificador del local por defecto (debe pertenecer a {@code storeIds})
 * @param version versión optimista (obligatoria al actualizar vía {@code PUT /users/{id}})
 */
public record UserRequest(
        @NotBlank(message = "El nombre de usuario es obligatorio.")
        @Pattern(regexp = "^[a-z0-9._-]{3,50}$",
                message = "El nombre de usuario debe tener entre 3 y 50 caracteres (minúsculas, dígitos, '.', '_' o '-').")
        String username,

        @NotBlank(message = "El nombre completo es obligatorio.")
        @Size(min = 2, max = 150, message = "El nombre completo debe tener entre 2 y 150 caracteres.")
        String fullName,

        @Email(message = "El correo electrónico no tiene un formato válido.")
        @Size(max = 254, message = "El correo electrónico no puede superar los 254 caracteres.")
        String email,

        @Size(max = 20, message = "El RUT no puede superar los 20 caracteres.")
        String rut,

        @NotEmpty(message = "Debe asignar al menos un rol al usuario.")
        List<@NotNull(message = "El identificador de rol no puede ser nulo.") Long> roleIds,

        @NotNull(message = "La lista de locales es obligatoria.")
        List<@NotNull(message = "El identificador de local no puede ser nulo.") Long> storeIds,

        Long defaultStoreId,

        Long version
) {
}

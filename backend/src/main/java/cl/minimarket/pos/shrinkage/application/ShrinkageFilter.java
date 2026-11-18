package cl.minimarket.pos.shrinkage.application;

import cl.minimarket.pos.shrinkage.ShrinkageStatus;
import cl.minimarket.pos.shrinkage.ShrinkageType;

import java.time.LocalDate;

/**
 * Criterios opcionales para listar mermas de un local ({@code API.md} F.2).
 *
 * @param status estado de la merma
 * @param shrinkageType tipo de merma
 * @param productId identificador de producto
 * @param from fecha inicial inclusive en zona horaria de negocio
 * @param to fecha final inclusive en zona horaria de negocio
 */
public record ShrinkageFilter(
        ShrinkageStatus status,
        ShrinkageType shrinkageType,
        Long productId,
        LocalDate from,
        LocalDate to) {
}

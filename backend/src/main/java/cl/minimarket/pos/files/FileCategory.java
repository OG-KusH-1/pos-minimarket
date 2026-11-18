package cl.minimarket.pos.files;

/**
 * Categoría funcional de un archivo almacenado (MODELO_DATOS B.1 {@code stored_files.category}).
 *
 * <p>Determina los formatos permitidos, el tamaño máximo y los permisos de subida y descarga.
 */
public enum FileCategory {

    /** Imagen de producto (JPEG, PNG o WEBP, máximo 5 MB). */
    PRODUCT_IMAGE,

    /** Evidencia de merma (JPEG, PNG, WEBP o PDF, máximo 5 MB). */
    SHRINKAGE_EVIDENCE,

    /** Logo de la empresa (JPEG, PNG o WEBP, máximo 5 MB). */
    COMPANY_LOGO,

    /** Archivo de importación masiva (CSV o XLSX, máximo 20 MB). */
    IMPORT,

    /** Archivo exportado por el sistema (PDF, CSV o XLSX, máximo 20 MB). */
    EXPORT
}

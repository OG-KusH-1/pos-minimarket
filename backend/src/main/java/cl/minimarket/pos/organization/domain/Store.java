package cl.minimarket.pos.organization.domain;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entidad de local/sucursal ({@code stores}, MODELO_DATOS B.1).
 */
@Entity
@Table(name = "stores")
public class Store extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "code", nullable = false, unique = true, length = 10)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "address", nullable = false, length = 250)
    private String address;

    @Column(name = "commune", nullable = false, length = 100)
    private String commune;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "sii_branch_code", length = 20)
    private String siiBranchCode;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Constructor requerido por JPA. */
    public Store() {
    }

    public Long getId() {
        return id;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCommune() {
        return commune;
    }

    public void setCommune(String commune) {
        this.commune = commune;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getSiiBranchCode() {
        return siiBranchCode;
    }

    public void setSiiBranchCode(String siiBranchCode) {
        this.siiBranchCode = siiBranchCode;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}

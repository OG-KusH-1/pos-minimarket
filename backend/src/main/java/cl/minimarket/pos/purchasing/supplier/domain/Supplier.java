package cl.minimarket.pos.purchasing.supplier.domain;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Entidad de proveedor ({@code suppliers}, {@code MODELO_DATOS.md} B.4).
 */
@Entity
@Table(name = "suppliers")
public class Supplier extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rut", nullable = false, unique = true, length = 12)
    private String rut;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(name = "trade_name", length = 200)
    private String tradeName;

    @Column(name = "business_activity", length = 200)
    private String businessActivity;

    @Column(name = "address", length = 250)
    private String address;

    @Column(name = "commune", length = 100)
    private String commune;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_terms_type", nullable = false, length = 20)
    private PaymentTermsType paymentTermsType;

    @Column(name = "payment_terms_days", nullable = false)
    private int paymentTermsDays = 0;

    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays = 1;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "delivery_weekdays", columnDefinition = "smallint[]", nullable = false)
    private short[] deliveryWeekdays = new short[0];

    @Column(name = "notes")
    private String notes;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Constructor requerido por JPA. */
    public Supplier() {
    }

    public Long getId() {
        return id;
    }

    public String getRut() {
        return rut;
    }

    public void setRut(String rut) {
        this.rut = rut;
    }

    public String getLegalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String getTradeName() {
        return tradeName;
    }

    public void setTradeName(String tradeName) {
        this.tradeName = tradeName;
    }

    public String getBusinessActivity() {
        return businessActivity;
    }

    public void setBusinessActivity(String businessActivity) {
        this.businessActivity = businessActivity;
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public PaymentTermsType getPaymentTermsType() {
        return paymentTermsType;
    }

    public void setPaymentTermsType(PaymentTermsType paymentTermsType) {
        this.paymentTermsType = paymentTermsType;
    }

    public int getPaymentTermsDays() {
        return paymentTermsDays;
    }

    public void setPaymentTermsDays(int paymentTermsDays) {
        this.paymentTermsDays = paymentTermsDays;
    }

    public int getLeadTimeDays() {
        return leadTimeDays;
    }

    public void setLeadTimeDays(int leadTimeDays) {
        this.leadTimeDays = leadTimeDays;
    }

    public short[] getDeliveryWeekdays() {
        return deliveryWeekdays == null ? new short[0] : deliveryWeekdays.clone();
    }

    public void setDeliveryWeekdays(short[] deliveryWeekdays) {
        this.deliveryWeekdays = deliveryWeekdays == null ? new short[0] : deliveryWeekdays.clone();
    }

    /** Devuelve los días ISO de despacho (1..7) como lista inmutable de enteros. */
    public List<Integer> getDeliveryWeekdaysList() {
        if (deliveryWeekdays == null || deliveryWeekdays.length == 0) {
            return List.of();
        }
        List<Integer> list = new ArrayList<>(deliveryWeekdays.length);
        for (short day : deliveryWeekdays) {
            list.add((int) day);
        }
        return List.copyOf(list);
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}

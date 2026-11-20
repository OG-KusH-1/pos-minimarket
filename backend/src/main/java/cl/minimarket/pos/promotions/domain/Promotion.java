package cl.minimarket.pos.promotions.domain;

import cl.minimarket.pos.promotions.PromotionType;
import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Entidad raíz de promoción ({@code promotions}, {@code MODELO_DATOS.md} B.10).
 */
@Entity
@Table(name = "promotions")
public class Promotion extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "promotion_type", nullable = false, length = 20)
    private PromotionType promotionType;

    @Column(name = "priority", nullable = false)
    private int priority = 100;

    @Column(name = "stackable", nullable = false)
    private boolean stackable = false;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "days_of_week", columnDefinition = "smallint[]")
    private short[] daysOfWeek;

    @Column(name = "time_from")
    private LocalTime timeFrom;

    @Column(name = "time_to")
    private LocalTime timeTo;

    @Column(name = "all_stores", nullable = false)
    private boolean allStores = true;

    @Column(name = "percent", precision = 7, scale = 4)
    private BigDecimal percent;

    @Column(name = "amount", precision = 14, scale = 0)
    private BigDecimal amount;

    @Column(name = "buy_qty")
    private Integer buyQty;

    @Column(name = "pay_qty")
    private Integer payQty;

    @Column(name = "min_qty", precision = 12, scale = 3)
    private BigDecimal minQty;

    @Column(name = "combo_price", precision = 14, scale = 0)
    private BigDecimal comboPrice;

    @Column(name = "max_applications_per_sale")
    private Integer maxApplicationsPerSale;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @ElementCollection
    @CollectionTable(name = "promotion_stores", joinColumns = @JoinColumn(name = "promotion_id"))
    @Column(name = "store_id", nullable = false)
    private Set<Long> storeIds = new LinkedHashSet<>();

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<PromotionItem> items = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PromotionType getPromotionType() {
        return promotionType;
    }

    public void setPromotionType(PromotionType promotionType) {
        this.promotionType = promotionType;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isStackable() {
        return stackable;
    }

    public void setStackable(boolean stackable) {
        this.stackable = stackable;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public void setValidTo(Instant validTo) {
        this.validTo = validTo;
    }

    public short[] getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(short[] daysOfWeek) {
        this.daysOfWeek = daysOfWeek;
    }

    public LocalTime getTimeFrom() {
        return timeFrom;
    }

    public void setTimeFrom(LocalTime timeFrom) {
        this.timeFrom = timeFrom;
    }

    public LocalTime getTimeTo() {
        return timeTo;
    }

    public void setTimeTo(LocalTime timeTo) {
        this.timeTo = timeTo;
    }

    public boolean isAllStores() {
        return allStores;
    }

    public void setAllStores(boolean allStores) {
        this.allStores = allStores;
    }

    public BigDecimal getPercent() {
        return percent;
    }

    public void setPercent(BigDecimal percent) {
        this.percent = percent;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getBuyQty() {
        return buyQty;
    }

    public void setBuyQty(Integer buyQty) {
        this.buyQty = buyQty;
    }

    public Integer getPayQty() {
        return payQty;
    }

    public void setPayQty(Integer payQty) {
        this.payQty = payQty;
    }

    public BigDecimal getMinQty() {
        return minQty;
    }

    public void setMinQty(BigDecimal minQty) {
        this.minQty = minQty;
    }

    public BigDecimal getComboPrice() {
        return comboPrice;
    }

    public void setComboPrice(BigDecimal comboPrice) {
        this.comboPrice = comboPrice;
    }

    public Integer getMaxApplicationsPerSale() {
        return maxApplicationsPerSale;
    }

    public void setMaxApplicationsPerSale(Integer maxApplicationsPerSale) {
        this.maxApplicationsPerSale = maxApplicationsPerSale;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Set<Long> getStoreIds() {
        return storeIds;
    }

    public List<PromotionItem> getItems() {
        return items;
    }

    /**
     * Reemplaza el conjunto de locales habilitados para esta promoción.
     *
     * @param newStoreIds identificadores de local; vacío si {@code allStores = true}
     */
    public void replaceStoreIds(Collection<Long> newStoreIds) {
        this.storeIds.clear();
        if (newStoreIds != null) {
            this.storeIds.addAll(newStoreIds);
        }
    }

    /**
     * Reemplaza la lista de ítems de alcance de esta promoción, manteniendo la relación bidireccional.
     *
     * @param newItems nuevos ítems de alcance
     */
    public void replaceItems(List<PromotionItem> newItems) {
        this.items.clear();
        if (newItems != null) {
            for (PromotionItem item : newItems) {
                item.setPromotion(this);
                this.items.add(item);
            }
        }
    }
}

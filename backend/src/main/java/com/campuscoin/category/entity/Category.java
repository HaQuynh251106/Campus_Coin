package com.campuscoin.category.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "categories")
@DynamicUpdate
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "enum('INCOME','EXPENSE')")
    private CategoryType type;

    @Column(name = "icon", length = 50)
    private String icon;

    @Column(name = "color", length = 7, columnDefinition = "char(7)")
    private String color;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "sort_order", nullable = false, columnDefinition = "smallint")
    private Integer sortOrder;

    @Column(name = "is_active", nullable = false, columnDefinition = "tinyint(1)")
    private Boolean isActive;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected Category() {

    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isDefault() {
        return userId == null;
    }

    public String getName() {
        return name;
    }

    public CategoryType getType() {
        return type;
    }

    public String getIcon() {
        return icon;
    }

    public String getColor() {
        return color;
    }

    public String getDescription() {
        return description;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setType(CategoryType type) {
        this.type = type;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public static Category newPersonal(Long userId,
                                       String name,
                                       CategoryType type,
                                       String icon,
                                       String color,
                                       String description,
                                       Integer sortOrder,
                                       Boolean isActive) {
        Category category = new Category();
        category.userId = userId;
        category.createdBy = userId;
        category.name = name;
        category.type = type;
        category.icon = icon;
        category.color = color;
        category.description = description;
        category.sortOrder = sortOrder == null ? 0 : sortOrder;
        category.isActive = isActive == null ? Boolean.TRUE : isActive;
        return category;
    }
}

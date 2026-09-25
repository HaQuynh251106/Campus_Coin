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

/**
 * A row of {@code categories}.
 *
 * <p>One table holds two kinds of row, told apart by {@link #userId}:
 *
 * <ul>
 *   <li>{@code user_id IS NULL} - a system-wide default category, seeded in {@code db/05_seed.sql}
 *       and shared by every student. Only an administrator may change one (BR-06, module 11).</li>
 *   <li>{@code user_id NOT NULL} - a personal category owned by one student. This is the kind
 *       UC-06 is about, and the only kind this module writes.</li>
 * </ul>
 *
 * <p>{@code scope_key} is deliberately not mapped. It is a {@code VIRTUAL} generated column that
 * exists only so the single {@code uk_categories_scope_type_name} key can express "default names
 * must not collide" and "a student's own names must not collide" at once. Nothing reads it, and
 * mapping it would invite Hibernate to try to write a value the database computes itself.
 *
 * <p>{@link DynamicUpdate} is applied for the same reason it is on {@code User}: a request may
 * change one field, and a plain Hibernate {@code UPDATE} would write every mapped column back
 * with the values read when the request began. That would undo a concurrent change to
 * {@code is_active} or {@code sort_order} - fields a partial update is specifically meant to
 * leave alone. {@code @DynamicInsert} is not applied: the INSERT must still name every column,
 * which is why the factory method sets the ones the schema gives defaults for.
 */
@Entity
@Table(name = "categories")
@DynamicUpdate
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The owning student, or {@code null} for a system-wide default category.
     *
     * <p>Mapped as a plain identifier rather than an association: nothing in this module needs the
     * owner's row, and a {@code @ManyToOne} would load a full {@code User} - including its password
     * hash and token version - to answer a question about a name.
     */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    /**
     * {@code columnDefinition} reproduces the schema's ENUM, for the same reason it does on
     * {@code User}: Hibernate validates a column by the type name MySQL reports, and an enum
     * mapped with {@link EnumType#STRING} would otherwise be expected to be {@code varchar}.
     * Pinning it keeps {@code ddl-auto=validate} strict.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "enum('INCOME','EXPENSE')")
    private CategoryType type;

    /** An icon name the frontend resolves, such as {@code utensils}. Free text, nullable. */
    @Column(name = "icon", length = 50)
    private String icon;

    /**
     * A CSS hex colour, exactly {@code #RRGGBB}. The column is {@code CHAR(7)}, which is what
     * fixes the shape; the API validates the same pattern so a caller gets a field error instead
     * of a truncated value.
     */
    @Column(name = "color", length = 7, columnDefinition = "char(7)")
    private String color;

    @Column(name = "description", length = 255)
    private String description;

    /**
     * Display order within a type. The index {@code ix_categories_user_type_active} ends in
     * {@code sort_order}, which is what the schema provides for ordering a student's own list.
     */
    @Column(name = "sort_order", nullable = false, columnDefinition = "smallint")
    private Integer sortOrder;

    /**
     * BR-07: an unused category may be deleted, but one that is already referenced is
     * <em>retired</em> by setting this to false, so the records that point at it keep their
     * meaning. {@code trg_categories_before_delete} enforces the other half of that rule.
     */
    @Column(name = "is_active", nullable = false, columnDefinition = "tinyint(1)")
    private Boolean isActive;

    /**
     * The account that created the row. For a default category this is the administrator, which
     * is what {@code trg_categories_before_insert} inspects to enforce BR-06. For a personal
     * category it is simply the student who created it.
     */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected Category() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    /** BR-06: a default category belongs to everyone and is editable only by an administrator. */
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

    // --- Writable by UC-06 -----------------------------------------------------------------------
    // Setters exist only for the columns a student may change on their own category. There is
    // deliberately no setter for user_id: changing a row's scope would move ownership of every
    // record pointing at it, and trg_categories_before_update refuses it at the database anyway.

    /** UC-06. */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * UC-06. Allowed here, and refused by the database when it would rewrite history:
     * {@code trg_categories_before_update} blocks the change once a transaction, budget or
     * recurring rule points at the category. Deciding that in Java would be stricter than the
     * schema - a category nothing references may legitimately change type - so the rule is left
     * where it is enforced.
     */
    public void setType(CategoryType type) {
        this.type = type;
    }

    /** UC-06. */
    public void setIcon(String icon) {
        this.icon = icon;
    }

    /** UC-06. */
    public void setColor(String color) {
        this.color = color;
    }

    /** UC-06. */
    public void setDescription(String description) {
        this.description = description;
    }

    /** UC-06. */
    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** UC-06 / BR-07: false retires the category instead of deleting it. */
    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    /**
     * Builds a personal category owned by one student - the only kind UC-06 creates.
     *
     * <p>{@code user_id} is a parameter rather than a field the caller sets, so a personal
     * category cannot be built without an owner; {@code created_by} is filled with the same id.
     * The two values the schema gives defaults for are written explicitly, because Hibernate names
     * every mapped column in the INSERT it builds and an explicit {@code NULL} would override the
     * column default and then fail the NOT NULL constraint.
     *
     * <p>Nothing here decides whether the name is acceptable. Uniqueness within the caller's own
     * categories, and the ban on reusing a default category's name, are the database's
     * ({@code uk_categories_scope_type_name} and {@code trg_categories_before_insert}); the
     * service checks them first only so the caller receives a precise error.
     *
     * @param userId   the owning student, never null
     * @param name     already trimmed by the service
     * @param sortOrder {@code null} means "use the column default", which is 0
     * @param isActive  {@code null} means "use the column default", which is true
     */
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

package com.campuscoin.common.setting.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of {@code system_settings}.
 *
 * <p>Mapped read-only. VĐ-05 stores every business threshold here so an administrator can
 * retune it without a redeploy, and this application only ever reads those values — writing
 * them is the administrator module's job, through its own procedure.
 */
@Entity
@Table(name = "system_settings")
public class SystemSetting {

    @Id
    @Column(name = "setting_key", length = 60, nullable = false)
    private String settingKey;

    @Column(name = "setting_value", length = 255)
    private String settingValue;

    /**
     * The declared type of {@code setting_value}.
     *
     * <p>{@code columnDefinition} reproduces the schema's ENUM exactly. Hibernate validates a
     * column's type by name, and without this it would expect {@code varchar(255)} for a String
     * field and refuse to start against the real ENUM column. Pinning the definition keeps
     * {@code ddl-auto=validate} strict rather than relaxing it to {@code none}.
     */
    @Column(name = "value_type", nullable = false,
            columnDefinition = "enum('STRING','INT','DECIMAL','BOOLEAN','JSON')")
    private String valueType;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public String getSettingKey() {
        return settingKey;
    }

    public String getSettingValue() {
        return settingValue;
    }

    public String getValueType() {
        return valueType;
    }

    public String getDescription() {
        return description;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

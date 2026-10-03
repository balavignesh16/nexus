package com.nexus.device.capability.definition;

import com.nexus.device.capability.CapabilityDirection;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "capability_definitions")
public class CapabilityDefinition {
    @Id
    private UUID id;
    @Column(name = "capability_key", nullable = false, unique = true, updatable = false, length = 100)
    private String key;
    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;
    @Column(columnDefinition = "text")
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private CapabilityDirection direction;
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, updatable = false, length = 20)
    private CapabilityValueType valueType;
    @Column(length = 50)
    private String unit;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_units", columnDefinition = "jsonb")
    private List<String> allowedUnits;
    @Column(name = "minimum_value")
    private BigDecimal minimum;
    @Column(name = "maximum_value")
    private BigDecimal maximum;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enum_values", columnDefinition = "jsonb")
    private List<String> enumValues;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_commands", columnDefinition = "jsonb")
    private List<String> supportedCommands;
    @Column(name = "semantic_category", length = 100)
    private String semanticCategory;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "object_constraints", columnDefinition = "jsonb")
    private Map<String, Object> objectConstraints;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CapabilityDefinition() {}

    public CapabilityDefinition(UUID id, String key, String displayName, String description,
            CapabilityDirection direction, CapabilityValueType valueType, String unit,
            List<String> allowedUnits, BigDecimal minimum, BigDecimal maximum,
            List<String> enumValues, List<String> supportedCommands, String semanticCategory,
            Map<String, Object> objectConstraints, Map<String, Object> metadata) {
        this.id = id;
        this.key = key;
        this.displayName = displayName;
        this.description = description;
        this.direction = direction;
        this.valueType = valueType;
        this.unit = unit;
        this.allowedUnits = copy(allowedUnits);
        this.minimum = minimum;
        this.maximum = maximum;
        this.enumValues = copy(enumValues);
        this.supportedCommands = copy(supportedCommands);
        this.semanticCategory = semanticCategory;
        this.objectConstraints = objectConstraints == null ? Map.of() : Map.copyOf(objectConstraints);
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        validateDefinition();
    }

    public void update(String displayName, String description, String unit, List<String> allowedUnits,
            BigDecimal minimum, BigDecimal maximum, List<String> enumValues,
            List<String> supportedCommands, String semanticCategory,
            Map<String, Object> objectConstraints, Map<String, Object> metadata) {
        this.displayName = displayName;
        this.description = description;
        this.unit = unit;
        this.allowedUnits = copy(allowedUnits);
        this.minimum = minimum;
        this.maximum = maximum;
        this.enumValues = copy(enumValues);
        this.supportedCommands = copy(supportedCommands);
        this.semanticCategory = semanticCategory;
        this.objectConstraints = objectConstraints == null ? Map.of() : Map.copyOf(objectConstraints);
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        validateDefinition();
    }

    public void validateDefinition() {
        if (key == null || !key.matches("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")) {
            throw new IllegalArgumentException("Capability key must use lower_snake_case");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Capability displayName is required");
        }
        if (direction == null || valueType == null) {
            throw new IllegalArgumentException("Capability direction and valueType are required");
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Capability minimum cannot exceed maximum");
        }
        if (valueType != CapabilityValueType.NUMBER && (minimum != null || maximum != null)) {
            throw new IllegalArgumentException("Only NUMBER capabilities may declare numeric bounds");
        }
        if (valueType == CapabilityValueType.ENUM && (enumValues == null || enumValues.isEmpty())) {
            throw new IllegalArgumentException("ENUM capabilities require at least one enum value");
        }
        if (valueType != CapabilityValueType.ENUM && enumValues != null && !enumValues.isEmpty()) {
            throw new IllegalArgumentException("Only ENUM capabilities may declare enum values");
        }
        if (unit != null && allowedUnits != null && !allowedUnits.isEmpty() && !allowedUnits.contains(unit)) {
            throw new IllegalArgumentException("Default unit must be included in allowedUnits");
        }
        if (direction == CapabilityDirection.READ && supportedCommands != null && !supportedCommands.isEmpty()) {
            throw new IllegalArgumentException("READ capabilities cannot declare supported commands");
        }
    }

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
    private static List<String> copy(List<String> value) { return value == null ? List.of() : List.copyOf(value); }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public CapabilityDirection getDirection() { return direction; }
    public CapabilityValueType getValueType() { return valueType; }
    public String getUnit() { return unit; }
    public List<String> getAllowedUnits() { return allowedUnits; }
    public BigDecimal getMinimum() { return minimum; }
    public BigDecimal getMaximum() { return maximum; }
    public List<String> getEnumValues() { return enumValues; }
    public List<String> getSupportedCommands() { return supportedCommands; }
    public String getSemanticCategory() { return semanticCategory; }
    public Map<String, Object> getObjectConstraints() { return objectConstraints; }
    public Map<String, Object> getMetadata() { return metadata; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

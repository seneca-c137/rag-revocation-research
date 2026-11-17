package edu.academic.rag.accesscontrol.model;

/**
 * Hierarchical data classification levels for Attribute-Based Access Control (ABAC).
 */
public enum ClassificationLevel {
    PUBLIC(1),
    INTERNAL(2),
    CONFIDENTIAL(3),
    RESTRICTED(4);

    private final int level;

    ClassificationLevel(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    /**
     * Checks if clearance level satisfies or exceeds the document classification level.
     */
    public boolean satisfies(ClassificationLevel requiredLevel) {
        if (requiredLevel == null) {
            return true;
        }
        return this.level >= requiredLevel.level;
    }

    public static ClassificationLevel fromString(String val) {
        if (val == null) {
            return PUBLIC;
        }
        try {
            return ClassificationLevel.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PUBLIC;
        }
    }
}

package com.dede.aem.models;

/**
 * Represents a directed edge between two CodeNodes.
 */
public class Relationship {

    private final RelationshipType type;
    private final int confidence;

    public Relationship(RelationshipType type) {
        this(type, 100);
    }

    public Relationship(RelationshipType type, int confidence) {
        this.type = type;
        this.confidence = confidence;
    }

    public RelationshipType getType() {
        return type;
    }

    public int getConfidence() {
        return confidence;
    }

    @Override
    public String toString() {
        return type.name() + (confidence < 100 ? "(" + confidence + "%)" : "");
    }
}

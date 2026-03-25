package com.dede.domain.model;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class Relationship {
    /**
     * Unique identifier for this edge. Required for JGraphT's DirectedMultigraph
     * to distinguish between multiple edges of the same type between different nodes.
     */
    private final String id;
    private final RelationshipType type;

    private int confidence = 100;
    private final Map<String, String> properties = new HashMap<>();

    public Relationship(RelationshipType type) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
    }

    public Relationship(RelationshipType type, int confidence) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.confidence = confidence;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Relationship that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

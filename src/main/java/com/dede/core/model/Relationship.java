package com.dede.core.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Relationship {
    @EqualsAndHashCode.Include
    private final RelationshipType type;
    
    private int confidence = 100;
    private final Map<String, String> properties = new HashMap<>();

    public Relationship(RelationshipType type) {
        this.type = type;
    }

    public Relationship(RelationshipType type, int confidence) {
        this.type = type;
        this.confidence = confidence;
    }
}

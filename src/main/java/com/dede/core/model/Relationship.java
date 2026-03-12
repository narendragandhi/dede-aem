package com.dede.core.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.jgrapht.graph.DefaultEdge;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class Relationship extends DefaultEdge {
    private RelationshipType type;
    private Map<String, String> properties = new HashMap<>();

    public Relationship(RelationshipType type) {
        this.type = type;
    }

    @Override
    public Object getSource() {
        return super.getSource();
    }

    @Override
    public Object getTarget() {
        return super.getTarget();
    }

    @Override
    public String toString() {
        return "(" + getSource() + " : " + getTarget() + " : " + type + ")";
    }
}

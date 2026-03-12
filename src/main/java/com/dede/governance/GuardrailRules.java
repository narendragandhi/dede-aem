package com.dede.governance;

import lombok.Data;
import java.util.List;

@Data
public class GuardrailRules {
    private List<Rule> rules;

    @Data
    public static class Rule {
        private String id;
        private String description;
        private String sourcePattern; // Regex for node ID/Name
        private String targetPattern; // Regex for target node
        private String relationshipType; // WIRES_TO, CONSUMES, etc.
        private Constraint constraint; // ALLOW, DENY
    }

    public enum Constraint {
        ALLOW, DENY
    }
}

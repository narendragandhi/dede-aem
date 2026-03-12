package com.dede.governance;

import com.dede.core.model.CodeNode;
import com.dede.core.model.Relationship;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jgrapht.Graph;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class GovernanceEngine {

    private final ObjectMapper objectMapper;
    private final List<String> violations = new ArrayList<>();
    private GuardrailRules rules;

    public GovernanceEngine() {
        this.objectMapper = new ObjectMapper();
    }

    public void loadRules(File rulesFile) {
        try {
            if (rulesFile.exists()) {
                this.rules = objectMapper.readValue(rulesFile, GuardrailRules.class);
            }
        } catch (Exception e) {
            System.err.println("Error loading rules: " + e.getMessage());
        }
    }

    public List<String> validate(Graph<CodeNode, Relationship> graph) {
        violations.clear();
        if (rules == null) return violations;

        for (GuardrailRules.Rule rule : rules.getRules()) {
            Pattern srcPattern = Pattern.compile(rule.getSourcePattern());
            Pattern targetPattern = Pattern.compile(rule.getTargetPattern());

            graph.vertexSet().stream()
                .filter(node -> srcPattern.matcher(node.getId()).find())
                .forEach(source -> {
                    graph.outgoingEdgesOf(source).stream()
                        .map(graph::getEdgeTarget)
                        .filter(target -> targetPattern.matcher(target.getId()).find())
                        .forEach(target -> {
                            if (rule.getConstraint() == GuardrailRules.Constraint.DENY) {
                                violations.add("VIOLATION [" + rule.getId() + "]: " + source.getId() + 
                                    " must not link to " + target.getId() + " (" + rule.getDescription() + ")");
                            }
                        });
                });
        }
        return violations;
    }

    public void printViolations() {
        if (violations.isEmpty()) {
            System.out.println("⚖️  Architectural Guardrails: No violations found.");
        } else {
            System.out.println("⚖️  Architectural Guardrails:");
            violations.forEach(v -> System.out.println("   ❌ " + v));
        }
    }
}

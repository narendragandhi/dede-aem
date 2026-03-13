package com.dede.knowledge;

import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jgrapht.Graph;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class GovernanceEngine {

    private GuardrailRules rules;
    private final List<String> violations = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public void loadRules(File rulesFile) throws IOException {
        this.rules = mapper.readValue(rulesFile, GuardrailRules.class);
    }

    public void validate(Graph<CodeNode, Relationship> graph) {
        violations.clear();
        if (rules == null || rules.getRules() == null) return;

        for (GuardrailRules.Rule rule : rules.getRules()) {
            Pattern sourcePattern = Pattern.compile(rule.getSourcePattern());
            Pattern targetPattern = Pattern.compile(rule.getTargetPattern());

            graph.edgeSet().stream()
                .filter(e -> e.getType().name().equals(rule.getRelationshipType()))
                .forEach(e -> {
                    CodeNode source = graph.getEdgeSource(e);
                    CodeNode target = graph.getEdgeTarget(e);

                    boolean sourceMatch = sourcePattern.matcher(source.getId()).find() || sourcePattern.matcher(source.getName()).find();
                    boolean targetMatch = targetPattern.matcher(target.getId()).find() || targetPattern.matcher(target.getName()).find();

                    if (sourceMatch && targetMatch && rule.getConstraint() == GuardrailRules.Constraint.DENY) {
                        violations.add("Governance Violation: " + rule.getDescription() + 
                            " (" + source.getName() + " -> " + target.getName() + ")");
                    }
                });
        }
    }

    /**
     * TDD Implementation: BMAD Audit Logic.
     * Enforces that Platform layers cannot depend on Business layers.
     */
    public List<String> auditBmad(Graph<CodeNode, Relationship> graph) {
        List<String> bmadViolations = new ArrayList<>();
        
        graph.edgeSet().stream()
            .filter(e -> e.getType() == RelationshipType.WIRES_TO)
            .forEach(edge -> {
                CodeNode source = graph.getEdgeSource(edge);
                CodeNode target = graph.getEdgeTarget(edge);
                
                String sourceLayer = source.getProperties().get("bmad-layer");
                String targetLayer = target.getProperties().get("bmad-layer");
                
                if (sourceLayer != null && targetLayer != null) {
                    if (sourceLayer.equals("PLATFORM") && targetLayer.equals("BUSINESS")) {
                        bmadViolations.add("BMAD Violation: PLATFORM layer '" + source.getName() + 
                            "' cannot depend on BUSINESS layer '" + target.getName() + "'");
                    }
                }
            });
            
        return bmadViolations;
    }

    public void printViolations() {
        if (violations.isEmpty()) {
            System.out.println("✅ Governance: No violations found.");
        } else {
            System.out.println("❌ Governance Violations:");
            violations.forEach(v -> System.out.println("   - " + v));
        }
    }
}

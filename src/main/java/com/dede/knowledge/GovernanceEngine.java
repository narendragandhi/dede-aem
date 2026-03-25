package com.dede.knowledge;

import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import com.dede.exception.ConfigurationException;
import com.dede.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jgrapht.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class GovernanceEngine {

    private static final Logger log = LoggerFactory.getLogger(GovernanceEngine.class);

    private GuardrailRules rules;
    private final List<String> violations = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public void loadRules(File rulesFile) {
        log.info("Loading governance rules from: {}", rulesFile.getAbsolutePath());
        try {
            this.rules = mapper.readValue(rulesFile, GuardrailRules.class);
            log.info("Loaded {} governance rules", rules.getRules() != null ? rules.getRules().size() : 0);
        } catch (IOException e) {
            log.error("Failed to load governance rules: {}", e.getMessage(), e);
            throw new ConfigurationException(ErrorCode.RULES_PARSE_ERROR,
                "Failed to load governance rules: " + e.getMessage(), rulesFile.getAbsolutePath(), e);
        }
    }

    public void validate(Graph<CodeNode, Relationship> graph) {
        log.info("Validating graph against governance rules");
        violations.clear();
        if (rules == null || rules.getRules() == null) {
            log.debug("No governance rules configured, skipping validation");
            return;
        }

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
                        String violation = "Governance Violation: " + rule.getDescription() +
                            " (" + source.getName() + " -> " + target.getName() + ")";
                        violations.add(violation);
                        log.warn("Governance violation detected: {}", violation);
                    }
                });
        }

        log.info("Governance validation completed with {} violations", violations.size());
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

    public List<String> getViolations() {
        return Collections.unmodifiableList(violations);
    }

    public void printViolations() {
        if (violations.isEmpty()) {
            log.info("Governance: No violations found");
        } else {
            log.warn("Governance Violations found: {}", violations.size());
            violations.forEach(v -> log.warn("  - {}", v));
        }
    }
}

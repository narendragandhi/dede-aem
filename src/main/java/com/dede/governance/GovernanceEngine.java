package com.dede.governance;

import com.dede.core.GraphService;
import com.dede.core.model.CodeNode;
import com.dede.core.model.Relationship;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class GovernanceEngine {

    private final GraphService graphService;
    private final ObjectMapper objectMapper;

    public GovernanceEngine(GraphService graphService) {
        this.graphService = graphService;
        this.objectMapper = new ObjectMapper();
    }

    public List<String> validate(String rulesPath) {
        List<String> violations = new ArrayList<>();
        try {
            File rulesFile = new File(rulesPath);
            if (!rulesFile.exists()) return violations;

            GuardrailRules rules = objectMapper.readValue(rulesFile, GuardrailRules.class);
            
            for (GuardrailRules.Rule rule : rules.getRules()) {
                Pattern srcPattern = Pattern.compile(rule.getSourcePattern());
                Pattern targetPattern = Pattern.compile(rule.getTargetPattern());

                graphService.getAllNodes().stream()
                    .filter(node -> srcPattern.matcher(node.getId()).find())
                    .forEach(source -> {
                        graphService.getOutgoingNodes(source).stream()
                            .filter(target -> targetPattern.matcher(target.getId()).find())
                            .forEach(target -> {
                                if (rule.getConstraint() == GuardrailRules.Constraint.DENY) {
                                    violations.add("VIOLATION [" + rule.getId() + "]: " + source.getId() + 
                                        " must not link to " + target.getId() + " (" + rule.getDescription() + ")");
                                }
                            });
                    });
            }
        } catch (Exception e) {
            violations.add("Error loading rules: " + e.getMessage());
        }
        return violations;
    }
}

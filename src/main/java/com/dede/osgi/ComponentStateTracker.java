package com.dede.osgi;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tracks the runtime-equivalent state of OSGi components based on static analysis.
 *
 * <p>States mirror the OSGi DS lifecycle:
 * <ul>
 *   <li><b>ACTIVE</b> — all mandatory references are satisfied</li>
 *   <li><b>UNSATISFIED</b> — at least one mandatory reference has no provider</li>
 * </ul>
 *
 * <p>This is computed from {@link ReferenceValidator} violations — no running
 * OSGi container is required.
 */
@Component
public class ComponentStateTracker {

    private final ReferenceValidator referenceValidator;

    public ComponentStateTracker(ReferenceValidator referenceValidator) {
        this.referenceValidator = referenceValidator;
    }

    public enum ComponentState { ACTIVE, UNSATISFIED }

    /**
     * Computes the state of every OSGI_COMPONENT node in the current graph.
     *
     * @return map of component-id → {@link ComponentState}
     */
    public Map<String, ComponentState> computeStates() {
        List<ReferenceValidator.ReferenceViolation> violations = referenceValidator.validate();

        // Collect IDs of components that have at least one UNSATISFIED_MANDATORY violation
        Set<String> unsatisfied = violations.stream()
            .filter(v -> v.type() == ReferenceValidator.ViolationType.UNSATISFIED_MANDATORY)
            .map(ReferenceValidator.ReferenceViolation::componentId)
            .collect(Collectors.toSet());

        // Build state map for every component that appeared in any violation
        // plus all components involved in violations
        Map<String, ComponentState> states = new HashMap<>();

        // Include all components mentioned in violations (both satisfied and unsatisfied)
        for (var violation : violations) {
            String id = violation.componentId();
            states.put(id, unsatisfied.contains(id) ? ComponentState.UNSATISFIED : ComponentState.ACTIVE);
        }

        // Include components with no violations as ACTIVE
        // (they exist in the graph but have no reference issues)
        referenceValidator.getComponentIds().forEach(id ->
            states.computeIfAbsent(id, k -> ComponentState.ACTIVE));

        return states;
    }

    /**
     * Returns a concise summary of component states.
     */
    public StateSummary summarize() {
        Map<String, ComponentState> states = computeStates();
        long active = states.values().stream().filter(s -> s == ComponentState.ACTIVE).count();
        long unsatisfied = states.values().stream().filter(s -> s == ComponentState.UNSATISFIED).count();
        return new StateSummary((int) active, (int) unsatisfied, states.size());
    }

    public record StateSummary(int active, int unsatisfied, int total) {}
}

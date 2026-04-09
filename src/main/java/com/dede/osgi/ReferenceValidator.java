package com.dede.osgi;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates OSGi {@code @Reference} cardinalities against the dependency graph.
 *
 * <p>For each {@code OSGI_COMPONENT} node, inspects outgoing {@code CONSUMES} edges
 * to {@code OSGI_SERVICE} nodes and checks:
 * <ul>
 *   <li><b>1..1 (mandatory)</b> — exactly one provider required; zero = UNSATISFIED</li>
 *   <li><b>1..n (mandatory multiple)</b> — at least one provider required</li>
 *   <li><b>0..1 / 0..n (optional)</b> — zero providers is acceptable</li>
 * </ul>
 *
 * <p>When a {@code target} LDAP filter is present on the {@code CONSUMES} edge
 * (stored in the edge's properties), only services whose properties satisfy
 * the filter count as valid providers.
 */
@Component
public class ReferenceValidator {

    private static final Logger log = LoggerFactory.getLogger(ReferenceValidator.class);

    private final GraphService graphService;
    private final LdapFilterParser ldapFilterParser;

    public ReferenceValidator(GraphService graphService, LdapFilterParser ldapFilterParser) {
        this.graphService    = graphService;
        this.ldapFilterParser = ldapFilterParser;
    }

    /**
     * Validates all components in the current graph.
     *
     * @return list of validation violations (empty = all references satisfied)
     */
    public List<ReferenceViolation> validate() {
        List<ReferenceViolation> violations = new ArrayList<>();

        List<CodeNode> components = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.OSGI_COMPONENT)
            .toList();

        log.info("Validating references for {} OSGI_COMPONENT nodes", components.size());

        for (CodeNode component : components) {
            violations.addAll(validateComponent(component));
        }

        return violations;
    }

    private List<ReferenceViolation> validateComponent(CodeNode component) {
        List<ReferenceViolation> violations = new ArrayList<>();

        // Collect all OSGI_SERVICE nodes this component consumes (CONSUMES edges only)
        Set<CodeNode> consumedServices = graphService.getRelatedNodes(component, RelationshipType.CONSUMES).stream()
            .filter(n -> n.getType() == NodeType.OSGI_SERVICE)
            .collect(Collectors.toSet());

        for (CodeNode serviceInterface : consumedServices) {
            // Resolve cardinality from properties (default: 1..1 mandatory)
            String cardinality = getEdgeProperty(component, serviceInterface, "cardinality");
            if (cardinality == null) cardinality = "1..1";

            boolean mandatory = isMandatory(cardinality);
            boolean multiple  = isMultiple(cardinality);

            // Resolve LDAP target filter if present
            String targetFilter = getEdgeProperty(component, serviceInterface, "target");
            LdapFilterParser.FilterNode filter = ldapFilterParser.parse(targetFilter);

            // Find all providers of this service interface that match the filter
            List<CodeNode> providers = findProviders(serviceInterface, filter);

            boolean satisfied = multiple
                ? !providers.isEmpty()                  // 1..n: at least one
                : providers.size() == 1;                // 1..1: exactly one
            boolean tooMany = !multiple && providers.size() > 1;

            if (mandatory && providers.isEmpty()) {
                violations.add(new ReferenceViolation(
                    component.getId(), component.getName(),
                    serviceInterface.getId(), serviceInterface.getName(),
                    cardinality, ViolationType.UNSATISFIED_MANDATORY,
                    String.format("Component '%s' has mandatory @Reference to '%s'" +
                        " (cardinality %s) but no provider found%s.",
                        component.getName(), serviceInterface.getName(), cardinality,
                        targetFilter != null ? " matching filter " + targetFilter : ""),
                    "Register a component that provides the service '" + serviceInterface.getName() + "'."
                ));
            } else if (tooMany) {
                violations.add(new ReferenceViolation(
                    component.getId(), component.getName(),
                    serviceInterface.getId(), serviceInterface.getName(),
                    cardinality, ViolationType.AMBIGUOUS_REFERENCE,
                    String.format("Component '%s' has 1..1 @Reference to '%s' but %d providers found. " +
                        "OSGi will pick the highest-ranked — this may be non-deterministic.",
                        component.getName(), serviceInterface.getName(), providers.size()),
                    "Add @Reference(target=\"(service.ranking>=X)\") to pin the reference, " +
                    "or use 0..n cardinality."
                ));
            }
        }

        return violations;
    }

    /**
     * Finds all OSGI_COMPONENT nodes that provide the given service interface,
     * filtered by the LDAP predicate.
     */
    private List<CodeNode> findProviders(CodeNode serviceInterface, LdapFilterParser.FilterNode filter) {
        return graphService.getInboundRelatedNodes(serviceInterface, RelationshipType.PROVIDES).stream()
            .filter(n -> n.getType() == NodeType.OSGI_COMPONENT)
            .filter(provider -> {
                // Build property map from the provider's graph properties
                Map<String, String> props = new HashMap<>(provider.getProperties());
                props.put("objectClass", serviceInterface.getName());
                return filter.matches(props);
            })
            .toList();
    }

    /**
     * Reads a property from the edge between two nodes (stored on the relationship).
     * Falls back to the target node's properties.
     */
    private String getEdgeProperty(CodeNode from, CodeNode to, String key) {
        // Edge properties are stored on the target node as "edgeProp:<relType>:<key>"
        // or directly on the service node
        String onNode = to.getProperties().get(key);
        if (onNode != null) return onNode;
        // Also check from-node properties scoped by service name
        return from.getProperties().get(to.getName() + "." + key);
    }

    private boolean isMandatory(String cardinality) {
        return cardinality.startsWith("1");
    }

    private boolean isMultiple(String cardinality) {
        return cardinality.endsWith("n");
    }

    // --- Types ---

    public enum ViolationType {
        UNSATISFIED_MANDATORY,   // 1..1 or 1..n with zero providers
        AMBIGUOUS_REFERENCE      // 1..1 with multiple providers
    }

    public record ReferenceViolation(
        String componentId,
        String componentName,
        String serviceId,
        String serviceName,
        String cardinality,
        ViolationType type,
        String message,
        String recommendation
    ) {}
}

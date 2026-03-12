package com.dede.core;

import com.dede.core.model.CodeNode;
import com.dede.core.model.Relationship;
import com.dede.core.model.RelationshipType;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Logic for graph analysis and traversal.
 */
@Service
public class GraphAnalyzer {

    private final GraphRepository repository;

    public GraphAnalyzer(GraphRepository repository) {
        this.repository = repository;
    }

    public Set<CodeNode> getOutgoingNodes(CodeNode source) {
        return repository.getGraph().outgoingEdgesOf(source).stream()
                .map(repository.getGraph()::getEdgeTarget)
                .collect(Collectors.toSet());
    }

    public Set<CodeNode> getIncomingNodes(CodeNode target) {
        return repository.getGraph().incomingEdgesOf(target).stream()
                .map(repository.getGraph()::getEdgeSource)
                .collect(Collectors.toSet());
    }

    public Set<CodeNode> getRelatedNodes(CodeNode source, RelationshipType type) {
        return repository.getGraph().outgoingEdgesOf(source).stream()
                .filter(edge -> edge.getType() == type)
                .map(repository.getGraph()::getEdgeTarget)
                .collect(Collectors.toSet());
    }

    public Set<CodeNode> getInboundRelatedNodes(CodeNode target, RelationshipType type) {
        return repository.getGraph().incomingEdgesOf(target).stream()
                .filter(edge -> edge.getType() == type)
                .map(repository.getGraph()::getEdgeSource)
                .collect(Collectors.toSet());
    }

    public Set<CodeNode> getTransitiveIncomingNodes(CodeNode target) {
        Set<CodeNode> result = new HashSet<>();
        collectTransitiveInbound(target, result);
        result.remove(target);
        return result;
    }

    private void collectTransitiveInbound(CodeNode node, Set<CodeNode> visited) {
        if (visited.contains(node)) return;
        visited.add(node);
        for (Relationship edge : repository.getGraph().incomingEdgesOf(node)) {
            collectTransitiveInbound(repository.getGraph().getEdgeSource(edge), visited);
        }
    }

    public Set<Set<CodeNode>> findCycles() {
        CycleDetector<CodeNode, Relationship> cycleDetector = new CycleDetector<>(repository.getGraph());
        Set<CodeNode> cycleNodes = cycleDetector.findCycles();
        return cycleNodes.isEmpty() ? Set.of() : Set.of(cycleNodes);
    }
}

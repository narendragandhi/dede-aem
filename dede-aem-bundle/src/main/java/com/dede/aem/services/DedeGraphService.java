package com.dede.aem.services;

import com.dede.aem.models.CodeNode;
import com.dede.aem.models.NodeType;
import com.dede.aem.models.Relationship;
import com.dede.aem.models.RelationshipType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service interface for the Dede architectural graph.
 * Provides methods to build, query, and analyze the dependency graph.
 */
public interface DedeGraphService {

    /**
     * Add a node to the graph.
     */
    void addNode(CodeNode node);

    /**
     * Add an edge between two nodes.
     */
    Relationship addEdge(CodeNode source, CodeNode target, RelationshipType type);

    /**
     * Add an edge with a confidence score.
     */
    Relationship addEdge(CodeNode source, CodeNode target, RelationshipType type, int confidence);

    /**
     * Find a node by its ID.
     */
    Optional<CodeNode> findNodeById(String id);

    /**
     * Find all nodes of a specific type.
     */
    List<CodeNode> findNodesByType(NodeType type);

    /**
     * Get all nodes in the graph.
     */
    Set<CodeNode> getAllNodes();

    /**
     * Get total node count.
     */
    int getNodeCount();

    /**
     * Get total edge count.
     */
    int getEdgeCount();

    /**
     * Get nodes that have edges pointing TO the given node.
     */
    Set<CodeNode> getIncomingNodes(CodeNode node);

    /**
     * Get nodes that the given node has edges pointing TO.
     */
    Set<CodeNode> getOutgoingNodes(CodeNode node);

    /**
     * Get related nodes by relationship type.
     */
    Set<CodeNode> getRelatedNodes(CodeNode node, RelationshipType type);

    /**
     * Detect circular dependencies in the graph.
     */
    Set<Set<CodeNode>> findCycles();

    /**
     * Get transitive dependencies (full dependency tree).
     */
    Set<CodeNode> getTransitiveDependencies(CodeNode node);

    /**
     * Get transitive dependents (what depends on this, recursively).
     */
    Set<CodeNode> getTransitiveDependents(CodeNode node);

    /**
     * Export graph to JSON format.
     */
    String exportToJson();

    /**
     * Get graph statistics.
     */
    Map<String, Object> getStatistics();

    /**
     * Clear all nodes and edges.
     */
    void clear();

    /**
     * Scan a JCR path and populate the graph.
     */
    void scanJcrPath(String path);

    /**
     * Get refactoring suggestions based on graph analysis.
     */
    List<String> suggestRefactoring();
}

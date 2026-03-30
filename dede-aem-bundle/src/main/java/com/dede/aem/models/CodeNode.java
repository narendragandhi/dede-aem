package com.dede.aem.models;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a node in the architectural dependency graph.
 * Can represent code (Classes, Methods), infrastructure (Dispatcher Filters),
 * or content (JCR Nodes, Components).
 */
public class CodeNode {

    private final String id;
    private final String name;
    private NodeType type;
    private final String signature;
    private final String path;
    private final Map<String, String> properties;

    public CodeNode(String id, String name, NodeType type, String signature, String path) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.signature = signature;
        this.path = path;
        this.properties = new HashMap<>();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public NodeType getType() {
        return type;
    }

    public void setType(NodeType type) {
        this.type = type;
    }

    public String getSignature() {
        return signature;
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CodeNode)) return false;
        CodeNode codeNode = (CodeNode) o;
        return id.equals(codeNode.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CodeNode{id='" + id + "', type=" + type + ", name='" + name + "'}";
    }
}

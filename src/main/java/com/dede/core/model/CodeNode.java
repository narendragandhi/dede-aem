package com.dede.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a single entity in the architectural Digital Twin.
 * Can represent code (Classes, Methods), infrastructure (Dispatcher Filters), 
 * or content (JCR Nodes).
 */
@Data
@AllArgsConstructor
public class CodeNode {
    /** Unique ID used for graph indexing (e.g. pkg:com.example) */
    private final String id;
    
    /** Display name */
    private final String name;
    
    /** The structural type of the node */
    private NodeType type;
    
    /** Full technical signature */
    private final String signature;
    
    /** Local filesystem path */
    private final String filePath;
    
    /** Extensible metadata */
    private final Map<String, String> properties = new HashMap<>();

    public CodeNode(String id, String name, NodeType type, String signature, String filePath) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.signature = signature;
        this.filePath = filePath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CodeNode codeNode)) return false;
        return id.equals(codeNode.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

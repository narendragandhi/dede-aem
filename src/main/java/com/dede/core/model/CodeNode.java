package com.dede.core.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class CodeNode {
    private String id;          // Unique signature (e.g., "com.example.MyClass.myMethod(String)")
    private String name;        // Simple name (e.g., "myMethod")
    private NodeType type;
    private String signature;   // Full signature
    private String filePath;    // Source file location
    private Map<String, String> properties = new HashMap<>();

    public CodeNode(String id, String name, NodeType type, String signature, String filePath) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.signature = signature;
        this.filePath = filePath;
    }

    public CodeNode(String id, String name, NodeType type, String signature, String filePath, Map<String, String> properties) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.signature = signature;
        this.filePath = filePath;
        this.properties = properties;
    }
    
    // Helper for debugging
    @Override
    public String toString() {
        return "[" + type + "] " + id;
    }
}

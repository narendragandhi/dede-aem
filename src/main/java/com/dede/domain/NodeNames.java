package com.dede.domain;

import com.dede.domain.model.CodeNode;

import java.util.Comparator;
import java.util.List;

/** Resolves what a person types -- a node id, a fully qualified or a simple class name -- to one node. */
public final class NodeNames {

    private NodeNames() {}

    /**
     * A node by id, by fully qualified class name, or by simple class name when unambiguous.
     *
     * @throws IllegalArgumentException when nothing matches, or a simple name matches several classes
     */
    public static CodeNode resolve(GraphService graph, String name) {
        var exact = graph.findNodeById(name).or(() -> graph.findNodeById("class:" + name));
        if (exact.isPresent()) return exact.get();
        List<CodeNode> bySimpleName = graph.getAllNodes().stream()
            .filter(n -> n.getId().startsWith("class:") && n.getId().endsWith("." + name))
            .sorted(Comparator.comparing(CodeNode::getId))
            .toList();
        if (bySimpleName.size() == 1) return bySimpleName.get(0);
        if (bySimpleName.isEmpty()) {
            throw new IllegalArgumentException("No node or class named '" + name + "'.");
        }
        throw new IllegalArgumentException("'" + name + "' is ambiguous: "
            + bySimpleName.stream().map(CodeNode::getId).toList() + ". Use the fully qualified name.");
    }
}

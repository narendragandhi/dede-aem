package com.dede.discovery;

import java.nio.file.Path;

/**
 * Strategy interface for project parsers.
 */
public interface ProjectParser {
    /**
     * Determines if this parser can handle the given file.
     */
    boolean supports(Path path);

    /**
     * Parses the file and updates the graph.
     */
    void parse(Path path);
    
    /**
     * Optional priority (lower runs first).
     */
    default int getPriority() { return 100; }
}

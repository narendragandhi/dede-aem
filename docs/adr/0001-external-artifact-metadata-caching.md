# ADR 001: External Artifact Metadata Caching

## Status
Accepted

## Context
A product with 1,000+ OSGi bundles results in significant I/O overhead if every JAR is opened and parsed on every execution. We need to identify dependencies without "loading" the code into the JVM classpath.

## Decision
We will implement a **Metadata Cache** using a local JSON-based storage. 
- **Key**: Absolute path + File Size + Last Modified Timestamp (or SHA-256).
- **Value**: Scanned OSGi Metadata (Bundle Name, Exports, Imports).

## Consequences
- **Positive**: Subsequent scans of the same platform version will be near-instant (O(1) lookups).
- **Positive**: Low memory footprint as only the "skeleton" of the external platform is kept in the graph.
- **Negative**: The cache file needs to be managed (e.g., a `.dede-cache.json` in the user's home or project root).

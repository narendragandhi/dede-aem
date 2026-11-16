# Dede-Java Known Limitations

This document describes known limitations, false positive scenarios, and areas for improvement.

## Static Analysis Limitations

### 1. Runtime Context Is Only What You Give It
Without runtime inputs, dede is static analysis. With them (see [runtime-guide.md](runtime-guide.md))
it knows calls, requests and errors, but still cannot:
- Verify actual OSGi bundle wiring or which services are registered at runtime
- See requests the dispatcher or CDN served from cache: request counts on publish are cache
  misses, not page views
- Attribute requests for pages it has no content for: pages authored in AEM need `--content`
- Attribute work done by product code (Core Components, AEM itself): only project classes are
  matched
- Prove code is dead beyond the trace window: "instrumented but never called" covers that window
  only, and only for classes the agent config instrumented

### 1a. Runtime Input Limits
- Live `--content` uses QueryBuilder with a property-exists constraint; on very large
  repositories it can hit query limits. Export a content package instead.
- Shortened URLs (via `/etc/map`) are resolved only when exactly one page matches; multi-site
  projects with identical structures leave them unattributed.
- Findings without a line number (some graph checks) fall back to class-level call counts.
- CVEs are linked to code through OSGi bundles from built manifests: scan after `mvn package`.
- Snapshot comparisons use raw counts; compare trace/log windows of similar length.

### 2. Dispatcher Include Directives
The dispatcher parser does not follow `$include` directives. This may cause false positives like:
```
DISP-001 - Missing /filter section in dispatcher.any
```
When the actual `/filter` section is in an included `.farm` file.

**Workaround**: Ignore warnings on `dispatcher.any` if it only contains `$include` directives.

### 3. HTL Expression Complexity
The HTL parser uses regex-based matching which may miss:
- Deeply nested expressions
- Multi-line HTL blocks
- Complex expression options (`@ context='html', i18n, format='...'`)
- Use-API with runtime-resolved classes

**Impact**: Some HTL dependencies may not be detected.

## False Positive Scenarios

### Cloud Readiness Rules
| Rule | False Positive Scenario |
|------|------------------------|
| Mutable path access | Code that conditionally runs only on Author |
| Deprecated API | API usage in test code |
| Session handling | Proper resource resolver usage flagged |

### Dispatcher Security
| Rule | False Positive Scenario |
|------|------------------------|
| DISP-001 | Include-only dispatcher files |
| DISP-007 | Method filtering in included files |

### Content Package
| Rule | False Positive Scenario |
|------|------------------------|
| Hardcoded paths | Intentional references to known content |
| Vanity URLs | URLs managed by Dispatcher redirects |

## Comparison with Adobe BPA

Dede-Java is **not a replacement** for Adobe's Best Practices Analyzer (BPA).

| Capability | Dede-Java | Adobe BPA |
|------------|-----------|-----------|
| Static code analysis | ✓ | Limited |
| Live repository analysis | ✗ | ✓ |
| Oak index validation | ✗ | ✓ |
| Package content validation | Partial | ✓ |
| Custom code detection | ✓ | ✓ |
| Dependency graph | ✓ | ✗ |
| Security scanning | ✓ | Limited |

**Recommendation**: Use both tools together for comprehensive analysis.

## Performance Considerations

### Large Codebases
For projects with 1000+ Java files:
- Initial scan may take 30-60 seconds
- Memory usage scales with node count
- Consider using `--profiles` to limit scope

### Known Scaling Issues
- D3.js Web UI may slow down with 2000+ nodes
- GraphQL queries on large graphs may timeout

## Roadmap for Improvements

1. **HTL Parser Enhancement**
   - Consider using actual Sightly compiler
   - Add multi-line expression support

2. **Dispatcher Include Resolution**
   - Follow `$include` directives
   - Build complete rule set from fragments

3. **Incremental Analysis**
   - Cache previous scan results
   - Only re-analyze changed files

4. **UI Performance**
   - Add node virtualization
   - Implement hierarchical views

## Reporting Issues

If you encounter false positives:
1. Check if it's a known limitation above
2. Open a GitHub issue with:
   - File/code that triggered the issue
   - Expected vs actual behavior
   - Dede-Java version

## Suppressing Warnings

Usage-pattern findings (`THREAD_UNSAFE_FIELDS`, `UNCLOSED_RESOURCE_RESOLVER`, `HTTP_CLIENT_TIMEOUT`,
`HTTP_CLIENT_REUSE`, `DEPRECATED_DAM`) honor `@SuppressWarnings` on the flagged element or any
enclosing declaration, using the rule id or category name:
```java
@SuppressWarnings("AEM Rules:AEM-3") // long-lived session used for observation
private Session session;
```

Import-based findings cannot be suppressed in code yet; a `// dede-ignore` comment is planned.

Build output, `src/test/`, `it.tests`/`ui.tests`, `node_modules` and `.git` are already
skipped by default (see `ScanExclusions`). Custom exclusions via CLI are planned:
```bash
java -jar dede.jar /project --exclude "**/test/**"
```

(Note: `--exclude` and `// dede-ignore` are planned but not yet implemented)

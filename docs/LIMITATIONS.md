# Dede-Java Known Limitations

This document describes known limitations, false positive scenarios, and areas for improvement.

## Static Analysis Limitations

### 1. No Runtime Context
Dede-Java performs static analysis only. It cannot:
- Verify actual OSGi bundle wiring at runtime
- Check if services are actually registered/available
- Validate content that exists only in the repository (not source)

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

To suppress specific warnings, add comments in your code:
```java
// dede-ignore: CST-5
someDeprecatedApiCall();
```

Or exclude files via CLI:
```bash
java -jar dede.jar /project --exclude "**/test/**"
```

(Note: These features are planned but not yet implemented)

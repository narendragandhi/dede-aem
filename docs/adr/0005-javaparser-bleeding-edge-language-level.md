# ADR 005: JavaParser Configured to BLEEDING_EDGE Language Level

## Status
Accepted

## Context
Four classes (`SourceParser` -- the core class/graph builder used on every scan, `ForbiddenApiScanner`, `SlingJobParser`, `ServletSecurityAuditor`) constructed `new JavaParser()` with the default `ParserConfiguration`. That default's language level silently rejects records, switch expressions, pattern-matching `instanceof`, text blocks, and sealed classes: `parse()` just returns `isSuccessful() == false`, no exception, logged only at DEBUG ("Failed to parse {file}") -- easy to miss entirely.

Verified concretely by scanning Dede's own source (which uses records and sealed classes throughout): before the fix, ~30+ files failed to parse silently and the resulting graph was mostly empty; after, 0 parse failures and the graph built 2,964 nodes / 4,769 edges from the same input. Since AEM Cloud Service is a Java 17+ target, this wasn't an edge case -- it was blinding the tool's core feature on exactly the kind of codebase it exists to analyze.

## Decision
All four `JavaParser` instantiations now use `ParserConfiguration.LanguageLevel.BLEEDING_EDGE`, the most permissive option available in javaparser 3.25.7. Chosen over pinning to a specific level (e.g. `JAVA_21`) because Dede needs to parse arbitrary customer projects spanning Java 8 through 21+, and a pinned level would just move the silent-failure boundary rather than remove it.

## Consequences
- **Positive**: The tool's core value proposition (the dependency graph) now actually works on modern AEM Cloud Service codebases instead of silently returning near-empty results.
- **Positive**: Removes an entire class of "looks like it scanned successfully, graph is just mostly empty" failure mode that produces no error, just quietly wrong output.
- **Negative**: `BLEEDING_EDGE` includes preview-language features; a source file using genuinely invalid syntax (as opposed to unsupported-by-default syntax) will still fail, but now for a real reason.
- **Follow-up needed**: this bug's *symptom* (DEBUG-level "Failed to parse", not surfaced anywhere in CLI/report output) is itself worth fixing separately -- a scan that silently skips most files today produces no visible warning that anything is wrong.

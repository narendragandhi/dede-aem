package com.dede.cli;

import com.dede.cloud.CloudFindings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The command line of a scan, parsed and validated before any work starts. A usage mistake --
 * a mistyped gate level, a flag without its value, an option that needs another -- is a
 * {@link UsageException} (exit 2), never a scan that silently runs without it.
 */
public record CliOptions(
    String projectPath,
    String profiles,
    String rulesPath,
    String dotOutputPath,
    String analyzeNodeId,
    boolean checkSecurity,
    String dependencyCheckReportPath,
    String snapshotPath,
    String compareSnapshot,
    boolean cloudReadiness,
    String bpaReportPath,
    String sarifOutputPath,
    String suggestionsOutputPath,
    String jsonOutputPath,
    String failOn,
    boolean extendedChecks,
    String runtimeTracesPath,
    String otelConfigPath,
    String instrumentedConfigPath,
    int otelMaxClasses,
    String requestLogPath,
    List<String> contentSources,
    String errorLogPath,
    boolean mcp,
    boolean watch,
    List<String> unknownFlags) {

    /** A command line that can't run as given. */
    public static final class UsageException extends Exception {
        public UsageException(String message) { super(message); }
    }

    private static final Set<String> VALUE_FLAGS = Set.of(
        "--profiles", "--rules", "--dot", "--analyze", "--dependency-check-report", "--snapshot", "--compare",
        "--bpa-report", "--sarif", "--suggestions", "--json", "--fail-on", "--runtime-traces",
        "--emit-otel-config", "--otel-config", "--otel-max-classes", "--request-log", "--content", "--error-log");
    private static final Set<String> SWITCHES = Set.of(
        "--security", "--cloud-readiness", "--fail-on-critical", "--extended-checks", "--mcp", "--watch");

    /** Every supported option (the help text and the docs list the same). */
    public static final Set<String> KNOWN_FLAGS;
    static {
        Set<String> all = new java.util.HashSet<>(VALUE_FLAGS);
        all.addAll(SWITCHES);
        all.add("--help");
        all.add("-h");
        KNOWN_FLAGS = Set.copyOf(all);
    }

    /** True when the scan needs the findings (gates, reports, runtime joins, MCP). */
    public boolean needsFindings() {
        return sarifOutputPath != null || jsonOutputPath != null || failOn != null || runtimeTracesPath != null
            || otelConfigPath != null || errorLogPath != null || mcp;
    }

    /** {@code args[0]} is the project path; the rest are options. */
    public static CliOptions parse(String[] args) throws UsageException {
        if (args.length == 0 || args[0].startsWith("-")) {
            throw new UsageException("the first argument must be the project path");
        }
        String profiles = "aem";
        String rules = null, dot = null, analyze = null, dependencyCheck = null, snapshot = null, compare = null;
        String bpa = null, sarif = null, suggestions = null, json = null, failOn = null, traces = null;
        String emitOtel = null, otelConfig = null, requestLog = null, errorLog = null;
        int otelMaxClasses = 50;
        boolean security = false, cloudReadiness = false, extended = false, mcp = false, watch = false;
        List<String> content = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            String value = null;
            if (VALUE_FLAGS.contains(flag)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new UsageException(flag + " needs a value");
                }
                value = args[++i];
            }
            switch (flag) {
                case "--profiles" -> profiles = value;
                case "--rules" -> rules = value;
                case "--dot" -> dot = value;
                case "--analyze" -> analyze = value;
                case "--dependency-check-report" -> dependencyCheck = value;
                case "--snapshot" -> snapshot = value;
                case "--compare" -> compare = value;
                case "--bpa-report" -> bpa = value;
                case "--sarif" -> sarif = value;
                case "--suggestions" -> suggestions = value;
                case "--json" -> json = value;
                case "--runtime-traces" -> traces = value;
                case "--emit-otel-config" -> emitOtel = value;
                case "--otel-config" -> otelConfig = value;
                case "--request-log" -> requestLog = value;
                case "--content" -> content.add(value);
                case "--error-log" -> errorLog = value;
                case "--fail-on" -> {
                    try {
                        failOn = CloudFindings.parseThreshold(value);
                    } catch (IllegalArgumentException e) {
                        // A mistyped gate must not run as "no gate".
                        throw new UsageException("--fail-on: " + e.getMessage());
                    }
                }
                case "--otel-max-classes" -> {
                    try {
                        otelMaxClasses = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new UsageException("--otel-max-classes: not a number: " + value);
                    }
                    if (otelMaxClasses < 1) throw new UsageException("--otel-max-classes must be at least 1");
                }
                case "--fail-on-critical" -> { if (failOn == null) failOn = "CRITICAL"; }
                case "--security" -> security = true;
                case "--cloud-readiness" -> cloudReadiness = true;
                case "--extended-checks" -> extended = true;
                case "--mcp" -> mcp = true;
                case "--watch" -> watch = true;
                default -> {
                    if (flag.startsWith("-")) unknown.add(flag);
                }
            }
        }
        if (otelConfig != null && traces == null) {
            throw new UsageException("--otel-config needs --runtime-traces: it says which classes the traces were watching");
        }
        if (mcp && watch) {
            throw new UsageException("--mcp and --watch can't be combined: the MCP server serves one finished scan");
        }
        return new CliOptions(args[0], profiles, rules, dot, analyze, security, dependencyCheck, snapshot, compare,
            cloudReadiness, bpa, sarif, suggestions, json, failOn, extended, traces, emitOtel, otelConfig,
            otelMaxClasses, requestLog, List.copyOf(content), errorLog, mcp, watch, List.copyOf(unknown));
    }
}

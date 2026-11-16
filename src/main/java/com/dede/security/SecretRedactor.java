package com.dede.security;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Removes credentials and personal identifiers from free text that dede did not write: log
 * messages, configuration values, URLs. Pattern-based, so it lowers the chance of a leak; it is
 * not a guarantee that none remains.
 */
public final class SecretRedactor {

    public static final String REDACTED = "[REDACTED]";

    private SecretRedactor() {}

    private record Rule(Pattern pattern, String replacement) {}

    private static final String WORDS = "passw(?:or)?d|pwd|secret|token|api[_.-]?key|access[_.-]?key|credential";
    private static final String VALUE = "(\"[^\"]*\"|'[^']*'|[^\\s,;&\"']+)";

    private static final List<Rule> RULES = List.of(
        new Rule(Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{6,}"), "$1 " + REDACTED),
        // JSON Web Token: three base64url parts, the first two starting with an encoded '{"'.
        new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*"), REDACTED),
        new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)"), REDACTED),
        new Rule(Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"), REDACTED),
        new Rule(Pattern.compile("\\b(sk|pk|rk)_(live|test)_[A-Za-z0-9]{8,}"), REDACTED),
        new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"), REDACTED),
        // user:password@host inside a URL
        new Rule(Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://[^/\\s:@]+):[^/\\s@]+@"), "$1:" + REDACTED + "@"),
        // password=..., clientSecret=...: with '=' any name that contains a credential word.
        new Rule(Pattern.compile("(?i)((?:[\\w.-]*(?:" + WORDS + ")[\\w.-]*)[\"']?\\s*=\\s*)" + VALUE), "$1" + REDACTED),
        // "client.secret": "...", api_key: ...: with ':' the name must not look like a class
        // (InvalidTokenException: ..., com.acme.TokenService: ...), which is how log lines name their source.
        new Rule(Pattern.compile("(?<![\\w.$])(?=[\\w.\"'-]*?(?i:" + WORDS + "))"
            + "((?:[\"']?(?:[a-z_][A-Za-z0-9_-]*\\.)*[a-z_][A-Za-z0-9_-]*[\"']?|Password|Secret|Token|API[ _-]?[Kk]ey)\\s*:\\s*)" + VALUE),
            "$1" + REDACTED),
        new Rule(Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}\\b"), "[EMAIL]"));

    private static final Pattern SENSITIVE_NAME =
        Pattern.compile("passw(or)?d|pwd|secret|token|api[_.-]?key|access[_.-]?key|credential|private[_.-]?key");

    /** {@code text} with anything that looks like a credential or an email address replaced. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Rule rule : RULES) out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        return out;
    }

    /** Whether a property or field name says its value is a credential, e.g. {@code config.api.password}. */
    public static boolean isSensitiveName(String name) {
        return name != null && SENSITIVE_NAME.matcher(name.toLowerCase(Locale.ROOT)).find();
    }
}

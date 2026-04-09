package com.dede.osgi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Parses OSGi LDAP filter expressions used in {@code @Reference(target="...")} annotations.
 *
 * <p>Supports the RFC 4515 subset used by OSGi:
 * <ul>
 *   <li>Simple: {@code (property=value)}</li>
 *   <li>Wildcard: {@code (property=val*)} or {@code (property=*)}</li>
 *   <li>Presence: {@code (property=*)}</li>
 *   <li>AND: {@code (&(a=1)(b=2))}</li>
 *   <li>OR:  {@code (|(a=1)(b=2))}</li>
 *   <li>NOT: {@code (!(a=1))}</li>
 *   <li>Comparison: {@code (service.ranking>=100)}</li>
 * </ul>
 *
 * <p>The resulting {@link FilterNode} tree can be evaluated against a property map
 * to decide whether a service matches a given {@code @Reference(target=)} filter.
 */
@Component
public class LdapFilterParser {

    private static final Logger log = LoggerFactory.getLogger(LdapFilterParser.class);

    /**
     * Parses an LDAP filter string into an AST.
     *
     * @param filter the filter string, e.g. {@code (&(service.ranking>=100)(vendor=Acme))}
     * @return the root {@link FilterNode}, or {@link FilterNode#ANY} if filter is null/blank
     */
    public FilterNode parse(String filter) {
        if (filter == null || filter.isBlank()) return FilterNode.ANY;
        String trimmed = filter.trim();
        try {
            return parseNode(trimmed, new int[]{0});
        } catch (Exception e) {
            log.warn("Could not parse LDAP filter '{}': {}", filter, e.getMessage());
            return FilterNode.ANY; // fail-open: treat unparsable filters as match-all
        }
    }

    private FilterNode parseNode(String s, int[] pos) {
        skipWhitespace(s, pos);
        expect(s, pos, '(');

        char operator = s.charAt(pos[0]);
        if (operator == '&' || operator == '|' || operator == '!') {
            pos[0]++;
            List<FilterNode> children = new ArrayList<>();
            while (pos[0] < s.length() && s.charAt(pos[0]) == '(') {
                children.add(parseNode(s, pos));
                skipWhitespace(s, pos);
            }
            expect(s, pos, ')');
            return new FilterNode.Composite(
                operator == '&' ? Op.AND : operator == '|' ? Op.OR : Op.NOT,
                children
            );
        }

        // Simple attribute assertion: (attr op value)
        return parseAssertion(s, pos);
    }

    private FilterNode parseAssertion(String s, int[] pos) {
        // Read attribute name
        int start = pos[0];
        while (pos[0] < s.length() && !isComparisonOp(s, pos[0]) && s.charAt(pos[0]) != ')') {
            pos[0]++;
        }
        String attr = s.substring(start, pos[0]).trim();

        // Read comparison operator
        CompOp compOp = readCompOp(s, pos);

        // Read value (up to closing paren)
        start = pos[0];
        while (pos[0] < s.length() && s.charAt(pos[0]) != ')') {
            pos[0]++;
        }
        String value = s.substring(start, pos[0]);
        expect(s, pos, ')');

        return new FilterNode.Assertion(attr, compOp, value);
    }

    private boolean isComparisonOp(String s, int i) {
        char c = s.charAt(i);
        if (c == '=') return true;
        if (c == '>' || c == '<' || c == '~') return i + 1 < s.length() && s.charAt(i + 1) == '=';
        return false;
    }

    private CompOp readCompOp(String s, int[] pos) {
        char c = s.charAt(pos[0]);
        if (c == '=') { pos[0]++; return CompOp.EQ; }
        if (c == '>' && pos[0] + 1 < s.length() && s.charAt(pos[0]+1) == '=') { pos[0] += 2; return CompOp.GTE; }
        if (c == '<' && pos[0] + 1 < s.length() && s.charAt(pos[0]+1) == '=') { pos[0] += 2; return CompOp.LTE; }
        if (c == '~' && pos[0] + 1 < s.length() && s.charAt(pos[0]+1) == '=') { pos[0] += 2; return CompOp.APPROX; }
        pos[0]++; return CompOp.EQ; // fallback
    }

    private void expect(String s, int[] pos, char c) {
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == c) {
            pos[0]++;
        }
    }

    private void skipWhitespace(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) pos[0]++;
    }

    // --- AST Node Types ---

    public enum Op    { AND, OR, NOT }
    public enum CompOp { EQ, GTE, LTE, APPROX }

    public sealed interface FilterNode permits FilterNode.Any, FilterNode.Composite, FilterNode.Assertion {

        /** Matches any property map — used as fail-open sentinel. */
        FilterNode ANY = new Any();

        /**
         * Evaluates this filter against a map of service properties.
         * @param properties service properties (e.g. from OSGi config or component annotation)
         * @return true if the service satisfies this filter
         */
        boolean matches(Map<String, String> properties);

        record Any() implements FilterNode {
            @Override public boolean matches(Map<String, String> p) { return true; }
            @Override public String toString() { return "(*)"; }
        }

        record Composite(LdapFilterParser.Op op, List<FilterNode> children) implements FilterNode {
            @Override
            public boolean matches(Map<String, String> properties) {
                return switch (op) {
                    case AND -> children.stream().allMatch(c -> c.matches(properties));
                    case OR  -> children.stream().anyMatch(c -> c.matches(properties));
                    case NOT -> !children.isEmpty() && !children.get(0).matches(properties);
                };
            }
        }

        record Assertion(String attribute, LdapFilterParser.CompOp op, String value) implements FilterNode {
            @Override
            public boolean matches(Map<String, String> properties) {
                String actual = properties.get(attribute);
                if (actual == null) return false;

                if ("*".equals(value)) return true; // presence check

                return switch (op) {
                    case EQ -> matchesGlob(actual, value);
                    case GTE -> compareNumeric(actual, value) >= 0;
                    case LTE -> compareNumeric(actual, value) <= 0;
                    case APPROX -> actual.equalsIgnoreCase(value);
                };
            }

            private boolean matchesGlob(String actual, String pattern) {
                if (!pattern.contains("*")) return actual.equals(pattern);
                // Simple glob: split on *, check prefix/suffix/contains
                String[] parts = pattern.split("\\*", -1);
                int idx = 0;
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].isEmpty()) continue;
                    int found = actual.indexOf(parts[i], idx);
                    if (found == -1) return false;
                    if (i == 0 && !actual.startsWith(parts[0])) return false;
                    idx = found + parts[i].length();
                }
                if (!pattern.endsWith("*") && !actual.endsWith(parts[parts.length - 1])) return false;
                return true;
            }

            private int compareNumeric(String a, String b) {
                try { return Double.compare(Double.parseDouble(a), Double.parseDouble(b)); }
                catch (NumberFormatException e) { return a.compareTo(b); }
            }
        }
    }

}

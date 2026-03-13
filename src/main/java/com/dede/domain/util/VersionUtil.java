package com.dede.domain.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Basic OSGi Version and Range matching utility.
 */
public class VersionUtil {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)(\\.(\\d+))?(\\.(\\d+))?.*");

    public static boolean matches(String version, String range) {
        if (range == null || range.isEmpty() || range.equals("0.0.0")) return true;
        
        // Simplified matching: handle [1.0, 2.0)
        if (range.startsWith("[") || range.startsWith("(")) {
            String[] parts = range.substring(1, range.length() - 1).split(",");
            if (parts.length == 1) return isGreaterOrEqual(version, parts[0].trim());
            
            boolean lowerMatch = range.startsWith("[") 
                ? isGreaterOrEqual(version, parts[0].trim()) 
                : isGreater(version, parts[0].trim());
            
            boolean upperMatch = range.endsWith("]") 
                ? isLessOrEqual(version, parts[1].trim()) 
                : isLess(version, parts[1].trim());
                
            return lowerMatch && upperMatch;
        }
        
        return isGreaterOrEqual(version, range);
    }

    private static boolean isGreaterOrEqual(String v1, String v2) {
        return compare(v1, v2) >= 0;
    }

    private static boolean isGreater(String v1, String v2) {
        return compare(v1, v2) > 0;
    }

    private static boolean isLessOrEqual(String v1, String v2) {
        return compare(v1, v2) <= 0;
    }

    private static boolean isLess(String v1, String v2) {
        return compare(v1, v2) < 0;
    }

    private static int compare(String v1, String v2) {
        int[] n1 = parse(v1);
        int[] n2 = parse(v2);
        for (int i = 0; i < 3; i++) {
            if (n1[i] < n2[i]) return -1;
            if (n1[i] > n2[i]) return 1;
        }
        return 0;
    }

    private static int[] parse(String v) {
        int[] res = new int[3];
        Matcher m = VERSION_PATTERN.matcher(v);
        if (m.matches()) {
            res[0] = Integer.parseInt(m.group(1));
            if (m.group(3) != null) res[1] = Integer.parseInt(m.group(3));
            if (m.group(5) != null) res[2] = Integer.parseInt(m.group(5));
        }
        return res;
    }
}

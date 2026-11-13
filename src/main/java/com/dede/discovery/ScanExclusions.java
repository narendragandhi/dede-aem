package com.dede.discovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Default directories every scan skips: build output, VCS/tooling folders, and test code.
 *
 * <p>Without this, scanning a built AEM project parses {@code ui.apps/target/generated-sources/htl}
 * (Java compiled from HTL) and integration-test modules, inflating the graph and producing
 * findings for code that never ships.
 *
 * <p>Rules, applied only to path segments <em>below</em> the scan root (so scanning a
 * {@code target} or {@code src/test} folder directly still works):
 * <ul>
 *   <li>{@code .git}, {@code node_modules}, and the AEM archetype test modules
 *       {@code it.tests} / {@code ui.tests} are always skipped. None of these can be a Java
 *       package name, so they never hide source code.</li>
 *   <li>{@code target} is skipped only when its parent holds a {@code pom.xml}, so a Java
 *       package named {@code target} is still scanned.</li>
 *   <li>{@code test} is skipped only directly under {@code src}, i.e. Maven's {@code src/test}.</li>
 * </ul>
 */
public final class ScanExclusions {

    private static final Set<String> ALWAYS_EXCLUDED = Set.of(".git", "node_modules", "it.tests", "ui.tests");

    private ScanExclusions() {
    }

    /** True if {@code path} lies inside a default-excluded directory below {@code root}. */
    public static boolean isExcluded(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return false;
        }

        Path parent = normalizedRoot;
        for (Path segment : normalizedRoot.relativize(normalizedPath)) {
            String name = segment.toString();
            if (ALWAYS_EXCLUDED.contains(name)) {
                return true;
            }
            if ("target".equals(name) && Files.exists(parent.resolve("pom.xml"))) {
                return true;
            }
            if ("test".equals(name) && parent.getFileName() != null
                    && "src".equals(parent.getFileName().toString())) {
                return true;
            }
            parent = parent.resolve(segment);
        }
        return false;
    }
}

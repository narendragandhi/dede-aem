package com.dede.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScanExclusionsTest {

    @Test
    void skipsMavenTargetButNotAJavaPackageNamedTarget(@TempDir Path root) throws IOException {
        Path module = Files.createDirectories(root.resolve("ui.apps"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");

        Path generated = module.resolve("target/generated-sources/htl/Foo.java");
        Path pkg = root.resolve("core/src/main/java/com/acme/target/Foo.java");

        assertThat(ScanExclusions.isExcluded(root, generated)).isTrue();
        assertThat(ScanExclusions.isExcluded(root, pkg)).isFalse();
    }

    @Test
    void skipsSrcTestAndTestModulesButNotMainCode(@TempDir Path root) {
        assertThat(ScanExclusions.isExcluded(root, root.resolve("core/src/test/java/FooTest.java"))).isTrue();
        assertThat(ScanExclusions.isExcluded(root, root.resolve("it.tests/src/main/java/GetPageIT.java"))).isTrue();
        assertThat(ScanExclusions.isExcluded(root, root.resolve("ui.frontend/node_modules/x/index.js"))).isTrue();
        assertThat(ScanExclusions.isExcluded(root, root.resolve("core/src/main/java/com/acme/test/Util.java"))).isFalse();
        assertThat(ScanExclusions.isExcluded(root, root.resolve("core/src/main/java/Foo.java"))).isFalse();
    }

    @Test
    void scanningAnExcludedFolderDirectlyStillWorks(@TempDir Path parent) throws IOException {
        Files.writeString(parent.resolve("pom.xml"), "<project/>");
        Path target = Files.createDirectories(parent.resolve("target"));

        assertThat(ScanExclusions.isExcluded(target, target.resolve("Foo.java"))).isFalse();
    }

    @Test
    void projectScannerDoesNotParseBuildOutput(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path generated = Files.createDirectories(root.resolve("target/generated-sources"));
        Files.writeString(generated.resolve("Generated.java"), "class Generated {}");
        Path main = Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(main.resolve("Real.java"), "class Real {}");

        com.dede.domain.GraphRepository repository = new com.dede.domain.GraphRepository();
        com.dede.domain.GraphService graphService = new com.dede.domain.GraphService(repository,
            new com.dede.domain.GraphAnalyzer(repository), new com.dede.domain.GraphExporter(repository));
        SourceParser sourceParser = org.mockito.Mockito.mock(SourceParser.class);
        DispatcherParser dispatcherParser = org.mockito.Mockito.mock(DispatcherParser.class);
        ApacheConfigParser apacheParser = org.mockito.Mockito.mock(ApacheConfigParser.class);
        ProjectScanner scanner = new ProjectScanner(sourceParser,
            org.mockito.Mockito.mock(OsgiManifestParser.class),
            org.mockito.Mockito.mock(SlingHtlParser.class),
            org.mockito.Mockito.mock(JcrContentParser.class),
            dispatcherParser, apacheParser, graphService);

        scanner.scan(root.toString());

        org.mockito.Mockito.verify(sourceParser).parse(main.resolve("Real.java"));
        org.mockito.Mockito.verify(sourceParser, org.mockito.Mockito.never())
            .parse(generated.resolve("Generated.java"));
    }
}

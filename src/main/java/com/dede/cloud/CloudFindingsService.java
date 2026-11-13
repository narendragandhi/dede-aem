package com.dede.cloud;

import com.dede.intelligence.CloudReadinessAnalyzer;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Collects {@link CloudFindings} for a scanned project. Call after the project has been scanned
 * into the graph (ProjectScanner + OsgiLinker), since the readiness checks read the graph.
 */
@Component
public class CloudFindingsService {

    private final ForbiddenApiScanner forbiddenApiScanner;
    private final CloudReadinessAnalyzer cloudReadinessAnalyzer;
    private final ExtendedChecks extendedChecks;

    public CloudFindingsService(ForbiddenApiScanner forbiddenApiScanner,
                                CloudReadinessAnalyzer cloudReadinessAnalyzer,
                                ExtendedChecks extendedChecks) {
        this.forbiddenApiScanner = forbiddenApiScanner;
        this.cloudReadinessAnalyzer = cloudReadinessAnalyzer;
        this.extendedChecks = extendedChecks;
    }

    public CloudFindings collect(Path projectRoot) {
        return collect(projectRoot, false);
    }

    /** @param extended also run {@link ExtendedChecks} (content packages, legacy paths, service users) */
    public CloudFindings collect(Path projectRoot, boolean extended) {
        return new CloudFindings(forbiddenApiScanner.scanProject(projectRoot), cloudReadinessAnalyzer.analyze(),
            extended ? extendedChecks.run(projectRoot) : java.util.List.of());
    }
}

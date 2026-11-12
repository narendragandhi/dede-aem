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

    public CloudFindingsService(ForbiddenApiScanner forbiddenApiScanner,
                                CloudReadinessAnalyzer cloudReadinessAnalyzer) {
        this.forbiddenApiScanner = forbiddenApiScanner;
        this.cloudReadinessAnalyzer = cloudReadinessAnalyzer;
    }

    public CloudFindings collect(Path projectRoot) {
        return new CloudFindings(forbiddenApiScanner.scanProject(projectRoot), cloudReadinessAnalyzer.analyze());
    }
}

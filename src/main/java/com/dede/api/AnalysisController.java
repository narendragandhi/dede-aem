package com.dede.api;

import com.dede.cloud.BpaReportGenerator;
import com.dede.cloud.BpaReportGenerator.BpaReport;
import com.dede.cloud.ForbiddenApiScanner;
import com.dede.cloud.SarifReport;
import com.dede.cloud.SupplyChainReport;
import com.dede.intelligence.CloudReadinessAnalyzer;
import com.dede.security.AclAnalyzer;
import com.dede.security.SecurityReport;
import com.dede.security.ServletSecurityAuditor;
import com.dede.security.XssDetector;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudReadinessReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

/**
 * REST API for cloud readiness analysis and BPA-compatible reports.
 */
@RestController
@RequestMapping("/api/analysis")
@Tag(name = "Analysis API", description = "Endpoints for cloud readiness analysis and BPA reports")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final CloudReadinessAnalyzer cloudReadinessAnalyzer;
    private final BpaReportGenerator bpaReportGenerator;
    private final ForbiddenApiScanner forbiddenApiScanner;
    private final ServletSecurityAuditor servletAuditor;
    private final XssDetector xssDetector;
    private final AclAnalyzer aclAnalyzer;

    public AnalysisController(CloudReadinessAnalyzer cloudReadinessAnalyzer,
                              BpaReportGenerator bpaReportGenerator,
                              ForbiddenApiScanner forbiddenApiScanner,
                              ServletSecurityAuditor servletAuditor,
                              XssDetector xssDetector,
                              AclAnalyzer aclAnalyzer) {
        this.cloudReadinessAnalyzer = cloudReadinessAnalyzer;
        this.bpaReportGenerator     = bpaReportGenerator;
        this.forbiddenApiScanner    = forbiddenApiScanner;
        this.servletAuditor         = servletAuditor;
        this.xssDetector            = xssDetector;
        this.aclAnalyzer            = aclAnalyzer;
    }

    @GetMapping("/cloud-readiness")
    @Operation(summary = "Cloud readiness analysis",
               description = "Analyzes the scanned codebase for AEM Cloud Service compatibility")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Analysis completed successfully"),
        @ApiResponse(responseCode = "500", description = "Analysis failed")
    })
    public ResponseEntity<CloudReadinessReport> getCloudReadiness() {
        log.info("Running cloud readiness analysis");
        CloudReadinessReport report = cloudReadinessAnalyzer.analyze();
        log.info("Cloud readiness analysis complete: score={}, issues={}",
                 report.getScore(), report.getIssues().size());
        return ResponseEntity.ok(report);
    }

    @GetMapping("/bpa-report")
    @Operation(summary = "BPA-compatible report (JSON)",
               description = "Generates a Best Practices Analyzer compatible report in JSON format")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Report generated successfully")
    })
    public ResponseEntity<BpaReport> getBpaReport(
            @Parameter(description = "Project name for the report")
            @RequestParam(defaultValue = "AEM Project") String projectName) {
        log.info("Generating BPA-compatible report for: {}", projectName);

        CloudReadinessReport cloudReport = cloudReadinessAnalyzer.analyze();
        BpaReport bpaReport = bpaReportGenerator.generateReport(cloudReport, projectName);

        return ResponseEntity.ok(bpaReport);
    }

    @GetMapping(value = "/bpa-report/html", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "BPA-compatible report (HTML)",
               description = "Generates a Best Practices Analyzer compatible report in HTML format")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "HTML report generated successfully",
                     content = @Content(mediaType = "text/html"))
    })
    public ResponseEntity<String> getBpaReportHtml(
            @Parameter(description = "Project name for the report")
            @RequestParam(defaultValue = "AEM Project") String projectName) {
        log.info("Generating BPA HTML report for: {}", projectName);

        CloudReadinessReport cloudReport = cloudReadinessAnalyzer.analyze();
        BpaReport bpaReport = bpaReportGenerator.generateReport(cloudReport, projectName);

        // Generate HTML directly instead of writing to file
        String html = generateHtmlReport(bpaReport);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
            .body(html);
    }

    @GetMapping(value = "/bpa-report/download", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Download BPA report (HTML)",
               description = "Downloads BPA report as an HTML file")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "HTML report downloaded successfully")
    })
    public ResponseEntity<String> downloadBpaReport(
            @Parameter(description = "Project name for the report")
            @RequestParam(defaultValue = "AEM Project") String projectName) {
        log.info("Downloading BPA HTML report for: {}", projectName);

        CloudReadinessReport cloudReport = cloudReadinessAnalyzer.analyze();
        BpaReport bpaReport = bpaReportGenerator.generateReport(cloudReport, projectName);
        String html = generateHtmlReport(bpaReport);

        String filename = "dede-bpa-report-" + projectName.replaceAll("[^a-zA-Z0-9]", "-") + ".html";

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
            .body(html);
    }

    @GetMapping("/cloud-readiness/text")
    @Operation(summary = "Cloud readiness report (text)",
               description = "Generates a text-based cloud readiness report for console output")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Text report generated successfully",
                     content = @Content(mediaType = "text/plain"))
    })
    public ResponseEntity<String> getCloudReadinessText() {
        log.info("Generating text-based cloud readiness report");

        CloudReadinessReport report = cloudReadinessAnalyzer.analyze();
        String textReport = cloudReadinessAnalyzer.generateTextReport(report);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
            .body(textReport);
    }

    @GetMapping("/supply-chain")
    @Operation(summary = "Supply-chain security scan",
               description = "Scans the project for supply-chain attack indicators: process spawning, remote classloading, and reflective payload delivery")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Scan completed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid project path")
    })
    public ResponseEntity<Map<String, Object>> getSupplyChainReport(
            @Parameter(description = "Absolute path to the project root to scan")
            @RequestParam String projectPath) {
        log.info("Running supply-chain security scan on: {}", projectPath);

        Path root = Path.of(projectPath);
        ForbiddenApiScanner.ScanResult scanResult = forbiddenApiScanner.scanProject(root);
        SupplyChainReport report = new SupplyChainReport(scanResult.violations(), root);

        Map<String, Object> summary = report.toSummary();
        log.info("Supply-chain scan complete: riskScore={}, findings={}",
                 report.getRiskScore(), report.getViolations().size());
        return ResponseEntity.ok(summary);
    }

    @GetMapping(value = "/supply-chain/sarif", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Supply-chain report (SARIF 2.1.0)",
               description = "Returns findings as SARIF 2.1.0 for GitHub Code Scanning, VS Code, and SIEMs")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "SARIF report generated successfully")
    })
    public ResponseEntity<Map<String, Object>> getSupplyChainSarif(
            @Parameter(description = "Absolute path to the project root to scan")
            @RequestParam String projectPath) {
        log.info("Generating SARIF supply-chain report for: {}", projectPath);

        Path root = Path.of(projectPath);
        ForbiddenApiScanner.ScanResult scanResult = forbiddenApiScanner.scanProject(root);
        SarifReport sarif = new SarifReport(scanResult.violations(), root);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/sarif+json")
            .body(sarif.toSarif());
    }

    @GetMapping(value = "/supply-chain/markdown", produces = "text/markdown")
    @Operation(summary = "Supply-chain report (Markdown)",
               description = "Returns the supply-chain security report as Markdown text")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Report generated successfully")
    })
    public ResponseEntity<String> getSupplyChainMarkdown(
            @Parameter(description = "Absolute path to the project root to scan")
            @RequestParam String projectPath) {
        log.info("Generating supply-chain Markdown report for: {}", projectPath);

        Path root = Path.of(projectPath);
        ForbiddenApiScanner.ScanResult scanResult = forbiddenApiScanner.scanProject(root);
        SupplyChainReport report = new SupplyChainReport(scanResult.violations(), root);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/markdown; charset=UTF-8")
            .body(report.toMarkdown());
    }

    @GetMapping("/security-report")
    @Operation(summary = "Unified security report (JSON)",
               description = "Runs all security analyzers (supply-chain, servlet, XSS, ACL) and returns a JSON summary")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Report generated successfully")
    })
    public ResponseEntity<Map<String, Object>> getSecurityReport(
            @Parameter(description = "Absolute path to the project root to scan")
            @RequestParam String projectPath) {
        log.info("Running unified security report for: {}", projectPath);
        SecurityReport report = buildSecurityReport(projectPath);
        return ResponseEntity.ok(report.toJson());
    }

    @GetMapping(value = "/security-report/markdown", produces = "text/markdown")
    @Operation(summary = "Unified security report (Markdown)")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Markdown report generated")
    })
    public ResponseEntity<String> getSecurityReportMarkdown(
            @Parameter(description = "Absolute path to the project root to scan")
            @RequestParam String projectPath) {
        log.info("Generating Markdown security report for: {}", projectPath);
        SecurityReport report = buildSecurityReport(projectPath);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/markdown; charset=UTF-8")
            .body(report.toMarkdown());
    }

    @GetMapping(value = "/security-report/sarif", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Unified security report (SARIF 2.1.0)")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "SARIF report generated")
    })
    public ResponseEntity<Map<String, Object>> getSecurityReportSarif(
            @Parameter(description = "Absolute path to the project root to scan")
            @RequestParam String projectPath) {
        log.info("Generating SARIF security report for: {}", projectPath);
        SecurityReport report = buildSecurityReport(projectPath);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/sarif+json")
            .body(report.toSarif());
    }

    private SecurityReport buildSecurityReport(String projectPath) {
        Path root = Path.of(projectPath);
        ForbiddenApiScanner.ScanResult scanResult = forbiddenApiScanner.scanProject(root);
        SupplyChainReport supplyChain = new SupplyChainReport(scanResult.violations(), root);
        ServletSecurityAuditor.AuditResult servletResult = servletAuditor.audit(root);
        XssDetector.ScanResult xssResult = xssDetector.scan(root);
        AclAnalyzer.AnalysisResult aclResult = aclAnalyzer.analyze(root);
        return new SecurityReport(supplyChain, servletResult, xssResult, aclResult, root);
    }

    /**
     * Generate HTML report inline (simplified version for API response).
     */
    private String generateHtmlReport(BpaReport report) {
        StringBuilder html = new StringBuilder();

        html.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Dede BPA-Compatible Report</title>
                <style>
                    :root {
                        --color-blocker: #d32f2f;
                        --color-critical: #f57c00;
                        --color-major: #ffa000;
                        --color-minor: #7cb342;
                        --color-bg: #fafafa;
                        --color-card: #ffffff;
                        --color-border: #e0e0e0;
                    }
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: var(--color-bg);
                        color: #333;
                        line-height: 1.6;
                    }
                    .container { max-width: 1200px; margin: 0 auto; padding: 20px; }
                    header {
                        background: linear-gradient(135deg, #eb1000 0%, #ff5722 100%);
                        color: white;
                        padding: 30px;
                        margin-bottom: 20px;
                        border-radius: 8px;
                    }
                    header h1 { font-size: 28px; margin-bottom: 8px; }
                    .summary-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
                        gap: 16px;
                        margin-bottom: 24px;
                    }
                    .summary-card {
                        background: var(--color-card);
                        border-radius: 8px;
                        padding: 20px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                        text-align: center;
                    }
                    .summary-card .value { font-size: 36px; font-weight: 700; }
                    .summary-card .label { font-size: 14px; color: #666; }
                    .summary-card.blocker .value { color: var(--color-blocker); }
                    .summary-card.critical .value { color: var(--color-critical); }
                    .summary-card.major .value { color: var(--color-major); }
                    .summary-card.score .value { color: #1976d2; }
                    .findings-section {
                        background: var(--color-card);
                        border-radius: 8px;
                        padding: 20px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                    }
                    .finding {
                        padding: 16px;
                        border: 1px solid var(--color-border);
                        border-radius: 6px;
                        margin-bottom: 12px;
                    }
                    .finding.BLOCKER { border-left: 4px solid var(--color-blocker); }
                    .finding.CRITICAL { border-left: 4px solid var(--color-critical); }
                    .finding.MAJOR { border-left: 4px solid var(--color-major); }
                    .finding.MINOR { border-left: 4px solid var(--color-minor); }
                    .finding-header { display: flex; justify-content: space-between; margin-bottom: 8px; }
                    .finding-rule { font-weight: 600; color: #1976d2; }
                    .finding-severity {
                        padding: 2px 8px;
                        border-radius: 4px;
                        font-size: 12px;
                        font-weight: 600;
                        color: white;
                    }
                    .finding-severity.BLOCKER { background: var(--color-blocker); }
                    .finding-severity.CRITICAL { background: var(--color-critical); }
                    .finding-severity.MAJOR { background: var(--color-major); color: #333; }
                    .finding-severity.MINOR { background: var(--color-minor); }
                    .finding-file {
                        font-family: monospace;
                        font-size: 13px;
                        color: #666;
                        background: #f5f5f5;
                        padding: 4px 8px;
                        border-radius: 4px;
                    }
                    footer { text-align: center; padding: 20px; color: #666; }
                </style>
            </head>
            <body>
                <div class="container">
            """);

        var summary = report.getSummary();
        html.append(String.format("""
                    <header>
                        <h1>AEM Cloud Service Readiness Report</h1>
                        <div>Project: %s | Generated by Dede-Java</div>
                    </header>
                    <div class="summary-grid">
                        <div class="summary-card score">
                            <div class="value">%d%%</div>
                            <div class="label">Score</div>
                        </div>
                        <div class="summary-card blocker">
                            <div class="value">%d</div>
                            <div class="label">Blockers</div>
                        </div>
                        <div class="summary-card critical">
                            <div class="value">%d</div>
                            <div class="label">Critical</div>
                        </div>
                        <div class="summary-card major">
                            <div class="value">%d</div>
                            <div class="label">Major</div>
                        </div>
                    </div>
            """, report.getProjectName(), summary.getCloudReadinessScore(),
                 summary.getBlockers(), summary.getCritical(), summary.getMajor()));

        html.append("<div class=\"findings-section\"><h2>Findings</h2>");
        for (var finding : report.getFindings()) {
            html.append(String.format("""
                        <div class="finding %s">
                            <div class="finding-header">
                                <span class="finding-rule">%s: %s</span>
                                <span class="finding-severity %s">%s</span>
                            </div>
                            <div>%s</div>
                            <div class="finding-file">%s</div>
                        </div>
                """, finding.getSeverity(), finding.getRuleId(), finding.getRuleName(),
                     finding.getSeverity(), finding.getSeverity(),
                     escapeHtml(finding.getMessage()), escapeHtml(finding.getFile())));
        }
        html.append("</div>");

        html.append("""
                    <footer>
                        Generated by Dede-Java v2.0.0
                    </footer>
                </div>
            </body>
            </html>
            """);

        return html.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }
}

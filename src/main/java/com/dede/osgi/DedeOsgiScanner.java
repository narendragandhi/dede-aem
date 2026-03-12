package com.dede.osgi;

import com.dede.core.GraphService;
import com.dede.analysis.ProjectScanner;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native AEM/OSGi Service for Dede-Java.
 * This can be called from AEM Groovy Console or MCP to scan the JCR/FS.
 */
@Component(service = DedeOsgiScanner.class, immediate = true)
public class DedeOsgiScanner {

    private static final Logger LOG = LoggerFactory.getLogger(DedeOsgiScanner.class);

    @Reference
    private GraphService graphService;

    @Reference
    private ProjectScanner projectScanner;

    public void scanAndReport(String rootPath) {
        LOG.info("Dede-Java OSGi: Starting scan for {}", rootPath);
        projectScanner.scan(rootPath);
        LOG.info("Dede-Java OSGi: Scan complete. Found {} nodes.", graphService.getNodeCount());
    }
}

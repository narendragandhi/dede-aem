package com.dede.osgi;

import com.dede.discovery.ProjectScanner;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@Component(service = DedeOsgiScanner.class)
public class DedeOsgiScanner {

    private static final Logger LOG = LoggerFactory.getLogger(DedeOsgiScanner.class);

    @Reference
    private ProjectScanner projectScanner;

    @Activate
    public void activate() {
        LOG.info("Dede OSGi Scanner Activated. Ready for in-container analysis.");
    }

    public void scanLocal(String path) {
        try {
            projectScanner.scan(path);
        } catch (IOException e) {
            LOG.error("Failed to scan path: {}", path, e);
        }
    }
}

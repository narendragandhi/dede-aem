package com.dede.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Single source of truth for XXE-hardened XML parsing (CWE-611). Dede parses XML
 * files (.content.xml, filter.xml, workflow models, client libs, OSGi service
 * descriptors, ...) discovered inside whatever project or content package it's
 * pointed at -- including third-party AEM packages under audit, per the README's
 * own "acquisition due diligence, unfamiliar codebases" use case. Every
 * DocumentBuilderFactory in this codebase must come from here rather than a bare
 * DocumentBuilderFactory.newInstance(), so a maliciously crafted package can't read
 * local files, hit internal network endpoints, or exhaust memory via entity
 * expansion when Dede parses it.
 */
public final class XmlSecurity {

    private static final Logger log = LoggerFactory.getLogger(XmlSecurity.class);

    private XmlSecurity() {
    }

    public static DocumentBuilderFactory newSafeDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            // The parser doesn't support disabling DOCTYPE/external entities via these
            // feature flags. Fail closed rather than parse with an unhardened factory.
            throw new IllegalStateException("XML parser does not support required XXE hardening features", e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}

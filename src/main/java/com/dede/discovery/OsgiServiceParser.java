package com.dede.discovery;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class OsgiServiceParser {

    public List<String> parseProvidedServices(InputStream is) {
        List<String> services = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);

            // <service><provide interface="..."/></service>
            NodeList provideNodes = doc.getElementsByTagName("provide");
            for (int i = 0; i < provideNodes.getLength(); i++) {
                Element element = (Element) provideNodes.item(i);
                String interfaceName = element.getAttribute("interface");
                if (!interfaceName.isEmpty()) {
                    services.add(interfaceName);
                }
            }
        } catch (Exception e) {
            // Log and skip
        }
        return services;
    }

    public List<String> parseConsumedServices(InputStream is) {
        List<String> services = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);

            // <reference interface="..." />
            NodeList refNodes = doc.getElementsByTagName("reference");
            for (int i = 0; i < refNodes.getLength(); i++) {
                Element element = (Element) refNodes.item(i);
                String interfaceName = element.getAttribute("interface");
                if (!interfaceName.isEmpty()) {
                    services.add(interfaceName);
                }
            }
        } catch (Exception e) {
            // Log and skip
        }
        return services;
    }
}

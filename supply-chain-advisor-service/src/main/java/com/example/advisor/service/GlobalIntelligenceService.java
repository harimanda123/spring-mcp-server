package com.example.advisor.service;

import com.example.advisor.model.GlobalEvent;
import com.example.advisor.repository.GlobalEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.Set;

/**
 * Scheduled service that polls the GDACS (Global Disaster Alert and Coordination System)
 * RSS feed every hour, persists disaster events whose {@code <gdacs:alertlevel>} matches
 * the configured severity list, and triggers an {@link AdviceService} re-evaluation for
 * every IN_TRANSIT shipment at the affected location.
 *
 * <p><b>Security</b>: The XML parser is configured with all recommended XXE mitigations
 * (OWASP A05 — Security Misconfiguration) before processing any external content.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalIntelligenceService {

    private static final String GDACS_RSS_URL =
            "https://www.gdacs.org/xml/rss.xml";

    /**
     * Severity levels that trigger persistence and shipment re-evaluation.
     * Sourced from {@code gdacs.alert.severities} in {@code application.properties}.
     * Defaults to {@code RED,ORANGE}. Values must be uppercase with no surrounding
     * whitespace (e.g. {@code gdacs.alert.severities=RED,ORANGE}).
     *
     * <p>Spring Boot's {@code StringToCollectionConverter} splits on commas and
     * populates the {@code Set<String>} automatically.
     */
    @Value("${gdacs.alert.severities:RED,ORANGE}")
    private Set<String> allowedSeverities;

    private final RestTemplate          restTemplate;
    private final GlobalEventRepository globalEventRepository;
    private final AdviceService         adviceService;

    // -----------------------------------------------------------------------
    // Scheduler
    // -----------------------------------------------------------------------

    /** Runs at the top of every hour. Adjust the cron expression for a different cadence. */
    @Scheduled(cron = "0 0 * * * *")
    public void updateGlobalRisks() {
        log.info("GlobalIntelligenceService: fetching GDACS alerts from {}", GDACS_RSS_URL);
        try {
            byte[] xmlBytes = restTemplate.getForObject(GDACS_RSS_URL, byte[].class);
            if (xmlBytes != null && xmlBytes.length > 0) {
                parseAndPersistAlerts(xmlBytes);
            }
        } catch (Exception ex) {
            // Log and swallow — a failed fetch must never crash the application
            log.error("Failed to fetch or process GDACS RSS feed", ex);
        }
    }

    // -----------------------------------------------------------------------
    // XML parsing
    // -----------------------------------------------------------------------

    private void parseAndPersistAlerts(byte[] xmlBytes) throws Exception {
        Document doc = buildSecureDocument(xmlBytes);
        doc.getDocumentElement().normalize();

        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            try {
                if (items.item(i) instanceof Element item) {
                    processItem(item);
                }
            } catch (Exception ex) {
                log.warn("Skipping malformed GDACS item at index {}: {}", i, ex.getMessage());
            }
        }
    }

    /**
     * Builds a {@link DocumentBuilder} with all XXE-prevention features enabled
     * as recommended by OWASP (A05 – Security Misconfiguration).
     *
     * <p>Accepts the raw response bytes so the XML parser can honour the encoding
     * declaration in the prolog (e.g. {@code encoding="UTF-8"}) without the
     * corruption that occurs when the bytes are first decoded to a Java String.
     * A UTF-8 BOM ({@code 0xEF 0xBB 0xBF}), if present, is stripped before
     * parsing to prevent the "Content is not allowed in prolog" SAXParseException.
     */
    private Document buildSecureDocument(byte[] xmlBytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable DOCTYPE declarations entirely
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        // Disable external general and parameter entity resolution
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        // Strip UTF-8 BOM (0xEF 0xBB 0xBF) when present — a common cause of
        // "Content is not allowed in prolog" with feeds served by some CDNs.
        int offset = 0;
        if (xmlBytes.length >= 3
                && (xmlBytes[0] & 0xFF) == 0xEF
                && (xmlBytes[1] & 0xFF) == 0xBB
                && (xmlBytes[2] & 0xFF) == 0xBF) {
            offset = 3;
            log.debug("UTF-8 BOM detected and stripped from GDACS response.");
        }

        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(
                new ByteArrayInputStream(xmlBytes, offset, xmlBytes.length - offset));
    }

    /**
     * Processes a single {@code <item>} element from the GDACS RSS feed.
     *
     * <p>The item is stored only when its {@code <gdacs:alertlevel>} value (e.g.,
     * {@code Red}, {@code Orange}) — compared case-insensitively against
     * {@link #allowedSeverities} — indicates a qualifying event. After persisting
     * the {@link GlobalEvent}, the {@link AdviceService} is triggered asynchronously
     * to assess all IN_TRANSIT shipments at the affected location using a
     * database-level filter (no full-table scan).
     */
    private void processItem(Element item) {
        String alertLevel = textContent(item, "gdacs:alertlevel").trim().toUpperCase();
        if (alertLevel.isBlank() || !allowedSeverities.contains(alertLevel)) {
            return;
        }

        String title     = textContent(item, "title");
        String eventType = extractEventType(title);
        String location  = extractLocation(title, item);

        if (location == null || location.isBlank()) {
            return;
        }

        GlobalEvent saved = upsertGlobalEvent(location, eventType, alertLevel);
        adviceService.evaluateInTransitShipmentsForEvent(saved);
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private GlobalEvent upsertGlobalEvent(String location, String eventType, String severity) {
        Optional<GlobalEvent> existing =
                globalEventRepository.findByLocationIgnoreCaseAndEventType(location, eventType);

        if (existing.isPresent()) {
            GlobalEvent record = existing.get();
            record.setSeverity(severity);
            record.setIsActive(true);
            GlobalEvent saved = globalEventRepository.save(record);
            log.info("Updated GlobalEvent: {} {} at '{}'", severity, eventType, location);
            return saved;
        } else {
            GlobalEvent newEvent = new GlobalEvent();
            newEvent.setLocation(location);
            newEvent.setEventType(eventType);
            newEvent.setSeverity(severity);
            newEvent.setIsActive(true);
            GlobalEvent saved = globalEventRepository.save(newEvent);
            log.info("Created GlobalEvent: {} {} at '{}'", severity, eventType, location);
            return saved;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String textContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : "";
    }

    /**
     * Extracts the disaster category from a GDACS title.
     * Expected format: "Red Alert for [EventType] [Name] in [Location]"
     */
    private String extractEventType(String title) {
        String[] tokens = title.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if ("for".equalsIgnoreCase(tokens[i])) {
                return tokens[i + 1];
            }
        }
        return "Unknown";
    }

    /**
     * Extracts the affected location from a GDACS RSS item.
     * Prefers the {@code gdacs:country} element; falls back to parsing
     * the " in [Location]" suffix from the title.
     */
    private String extractLocation(String title, Element item) {
        // Prefer the structured gdacs:country element when present
        NodeList countryNodes = item.getElementsByTagName("gdacs:country");
        if (countryNodes.getLength() > 0) {
            String country = countryNodes.item(0).getTextContent().trim();
            if (!country.isBlank()) {
                return country;
            }
        }
        // Fall back: parse "... in [Location]" from the title
        int inIdx = title.toLowerCase().lastIndexOf(" in ");
        if (inIdx >= 0) {
            return title.substring(inIdx + 4).trim();
        }
        return null;
    }
}

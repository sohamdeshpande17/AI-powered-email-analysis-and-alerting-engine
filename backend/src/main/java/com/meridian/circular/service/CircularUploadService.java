package com.meridian.circular.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.circular.domain.AppUser;
import com.meridian.circular.service.NasStorageService.StoredFile;
import com.meridian.circular.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Manual circular upload (every role) — the UI-side source service.
 *
 * <p>Mirrors the Python source pipeline exactly: mint a circular_id, save the
 * uploaded files to the shared NAS in the v3 layout, then publish the standard
 * {@code circular.raw.v1} envelope keyed by circular_id with a
 * {@code source_id=MANUAL} header. The circular-processor consumes it like any
 * scraped/emailed circular — AI analysis included — so a manually uploaded
 * circular lands in the Inbox through the same flow.
 *
 * <p>ZIP attachments are stored as-is AND every member file is extracted and
 * saved at the same circular_id level (the AI cannot read ZIPs directly); all
 * entries share the one circular_id.
 */
@Service
public class CircularUploadService {

    private static final Logger log = LoggerFactory.getLogger(CircularUploadService.class);

    private static final String SOURCE_ID = "MANUAL";

    private final NasStorageService nas;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json = new ObjectMapper();
    private final String rawTopic;

    public CircularUploadService(NasStorageService nas,
                                 KafkaTemplate<String, String> kafka,
                                 @Value("${app.kafka.raw-topic}") String rawTopic) {
        this.nas = nas;
        this.kafka = kafka;
        this.rawTopic = rawTopic;
    }

    /**
     * Save files to NAS, publish the raw envelope. Returns the minted
     * circular_id (the processor links everything back through it).
     */
    public UUID upload(String subject, String circularNo, String department,
                       String body, String issuedAt,
                       List<MultipartFile> files, AppUser actor) {
        if (subject == null || subject.isBlank()) {
            throw ApiException.badRequest("Subject is required.");
        }
        UUID circularId = UUID.randomUUID();
        List<Map<String, Object>> documents = new ArrayList<>();

        for (MultipartFile file : files == null ? List.<MultipartFile>of() : files) {
            if (file.isEmpty()) {
                continue;
            }
            byte[] content;
            try {
                content = file.getBytes();
            } catch (IOException e) {
                throw ApiException.badRequest(
                        "Could not read uploaded file: " + file.getOriginalFilename());
            }
            String filename = file.getOriginalFilename() != null
                    && !file.getOriginalFilename().isBlank()
                    ? Paths.get(file.getOriginalFilename()).getFileName().toString()
                    : "attachment";

            StoredFile stored = nas.storeRawDocument(SOURCE_ID, circularId, filename, content);
            documents.add(documentEntry("attachment", filename, stored,
                    mimeType(filename, file.getContentType())));

            if (isZip(filename, file.getContentType())) {
                extractZipMembers(circularId, filename, content, documents);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subject", subject.trim());
        payload.put("circular_no", blankToNull(circularNo));
        payload.put("department", blankToNull(department));
        payload.put("body", blankToNull(body));
        payload.put("issued_at", blankToNull(issuedAt));
        payload.put("uploaded_by_name", actor.displayName);
        payload.put("uploaded_by_email", actor.email);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_type", "circular.raw");
        envelope.put("event_version", "1");
        envelope.put("event_id", UUID.randomUUID().toString());
        envelope.put("occurred_at", Instant.now().toString());
        // MANUAL upload routes to the uploader's own workspace (processor honours
        // the envelope tenant_id for MANUAL; other sources fan out via source_tenant).
        envelope.put("tenant_id", actor.tenantId);
        envelope.put("source_id", SOURCE_ID);
        envelope.put("circular_id", circularId.toString());
        envelope.put("payload", payload);
        envelope.put("documents", documents);

        publish(circularId, envelope);
        log.info("Manual upload published: circular_id={} subject={} docs={} by={}",
                circularId, subject, documents.size(), actor.email);
        return circularId;
    }

    private void publish(UUID circularId, Map<String, Object> envelope) {
        try {
            String value = json.writeValueAsString(envelope);
            var record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                    rawTopic, circularId.toString(), value);
            record.headers().add("source_id", SOURCE_ID.getBytes());
            kafka.send(record).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not publish the circular for processing (Kafka): "
                            + e.getMessage());
        }
    }

    /** Extract every file inside a ZIP and save it at the same circular level. */
    private void extractZipMembers(UUID circularId, String zipName, byte[] zipBytes,
                                   List<Map<String, Object>> documents) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                // Flatten nested zip subdirs — basename only, same level.
                String name = Paths.get(entry.getName()).getFileName().toString();
                if (name.isBlank()) {
                    continue;
                }
                byte[] member = zis.readAllBytes();
                StoredFile stored = nas.storeRawDocument(SOURCE_ID, circularId, name, member);
                documents.add(documentEntry("archive_child", name, stored,
                        mimeType(name, null)));
                log.info("ZIP extracted: {} -> {} ({} bytes)", zipName, name, member.length);
            }
        } catch (IOException e) {
            log.warn("ZIP extraction failed for {} (circular_id={}): {}",
                    zipName, circularId, e.getMessage());
        }
    }

    private static Map<String, Object> documentEntry(String source, String filename,
                                                     StoredFile stored, String mime) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("document_source", source);
        doc.put("original_filename", filename);
        doc.put("nas_relative_path", stored.relativePath().replace('\\', '/'));
        doc.put("mime_type", mime);
        doc.put("size_bytes", stored.sizeBytes());
        doc.put("sha256", stored.sha256());
        return doc;
    }

    private static boolean isZip(String filename, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return filename.toLowerCase(Locale.ROOT).endsWith(".zip")
                || ct.equals("application/zip")
                || ct.equals("application/x-zip-compressed");
    }

    private static String mimeType(String filename, String hint) {
        if (hint != null && !hint.isBlank()) {
            return hint;
        }
        String guessed = URLConnection.guessContentTypeFromName(filename);
        return guessed != null ? guessed : "application/octet-stream";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

package com.meridian.circular.service;

import com.meridian.circular.service.NasStorageService.StoredFile;
import com.meridian.circular.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * NAS storage for circular workflow attachments (currently closure evidence),
 * deliberately rooted OUTSIDE the analyser document store: its own configurable
 * root {@code app.nas.attachments-root} (UAT/prod default
 * {@code /AI_CoE/circular_attachments}). Layout:
 * {@code <yyyy>/<mm>/<circular-no>/<token>__<filename>}. The stored relative
 * path is what lands in {@code circular_attachment.nas_relative_path}, so the
 * physical root can move without rewriting any database rows.
 *
 * <p>Mirrors {@link NasStorageService}'s path-traversal guard, filename
 * sanitising and SHA-256 hashing, but is a separate component so the two storage
 * areas stay cleanly isolated.
 */
@Service
public class EvidenceStorageService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceStorageService.class);
    private static final DateTimeFormatter YM =
            DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);

    private final Path root;

    public EvidenceStorageService(@Value("${app.nas.attachments-root}") String configuredRoot) {
        this.root = Paths.get(configuredRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("Could not pre-create attachments root {}: {}", root, e.getMessage());
        }
        log.info("Attachment storage root: {}", root);
    }

    /**
     * Write one attachment for a circular. {@code circularNo} routinely contains
     * slashes (e.g. NSE/CML/74960) — it is sanitised to a single path segment.
     * Returns the NAS-relative path, SHA-256 and size.
     */
    public StoredFile store(String circularNo, String filename, byte[] content) {
        String token = UUID.randomUUID().toString();
        String relative = YM.format(Instant.now()) + "/" + sanitise(circularNo)
                + "/" + token + "__" + sanitise(filename);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw ApiException.badRequest("Invalid attachment path.");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to write attachment to NAS: " + e.getMessage());
        }
        return new StoredFile(relative, sha256(content), content.length);
    }

    /** Read an attachment back by its stored relative path. */
    public byte[] read(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw ApiException.notFound("Attachment file");
        }
        String rel = relativePath.replace('\\', '/').replaceFirst("^/+", "");
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            log.warn("Attachment read miss: relativePath={} resolved={} (root={})",
                    relativePath, target, root);
            throw ApiException.notFound("Attachment file");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read attachment from NAS: " + e.getMessage());
        }
    }

    private static String sanitise(String name) {
        if (name == null || name.isBlank()) {
            return "attachment";
        }
        String n = name.replaceAll("[\\\\/]", "_").replaceAll("[:*?\"<>|]", "_");
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

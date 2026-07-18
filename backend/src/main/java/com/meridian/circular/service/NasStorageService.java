package com.meridian.circular.service;

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
 * NAS storage for attachment binaries (BRD FR-DATA — NAS holds files only).
 *
 * <p>The storage root is configurable via {@code app.nas.root}. It is a local
 * folder for now; pointing it at a real NAS share later is a config change
 * only (set the {@code NAS_ROOT} env var) — no code change. The path layout
 * follows the BRD: {@code circulars/<yyyy>/<mm>/<dd>/<circular-id>/attachments/}.
 */
@Service
public class NasStorageService {

    private static final Logger log = LoggerFactory.getLogger(NasStorageService.class);
    private static final DateTimeFormatter YMD =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    /** A stored file — its path relative to the NAS root, hash and size. */
    public record StoredFile(String relativePath, String sha256, long sizeBytes) {
    }

    private final Path root;

    public NasStorageService(@Value("${app.nas.root}") String configuredRoot) {
        this.root = Paths.get(configuredRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("Could not pre-create NAS root {}: {}", root, e.getMessage());
        }
        log.info("NAS storage root: {}", root);
    }

    /**
     * Write an attachment to the NAS. Returns the path relative to the NAS
     * root — that relative path is what gets stored in
     * {@code circular_document.nas_relative_path}, so the physical root can
     * change without rewriting any database rows.
     */
    public StoredFile store(UUID circularId, String filename,
                            byte[] content, Instant receivedAt) {
        Instant when = receivedAt != null ? receivedAt : Instant.now();
        String token = UUID.randomUUID().toString();
        String relative = "circulars/" + YMD.format(when) + "/" + circularId
                + "/attachments/" + token + "__" + sanitise(filename);

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

    /**
     * Write a raw-circular document in the v3 source layout —
     * {@code {SOURCE}/{yyyy}/{MM}/{circularId}/{filename}} — the same layout
     * the Python source services use and the circular-processor reads from.
     * Used by the manual-upload flow so the AI pipeline can find the files.
     */
    public StoredFile storeRawDocument(String sourceId, UUID circularId,
                                       String filename, byte[] content) {
        String ym = DateTimeFormatter.ofPattern("yyyy/MM")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String relative = sourceId.toUpperCase() + "/" + ym + "/" + circularId
                + "/" + sanitise(filename);

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

    /** Read an attachment back from the NAS by its stored relative path. */
    public byte[] read(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw ApiException.notFound("Document file");
        }
        // The Python pipeline records forward-slash relative paths, but stored
        // values have historically carried backslashes (Windows) or a leading
        // separator. Normalise to forward slashes and strip leading separators
        // so the path always resolves UNDER the NAS root instead of escaping it
        // (a leading "/" would otherwise resolve to the drive root and 404).
        String rel = relativePath.replace('\\', '/').replaceFirst("^/+", "");
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            log.warn("NAS read miss: relativePath={} resolved={} (root={})",
                    relativePath, target, root);
            throw ApiException.notFound("Document file");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read attachment from NAS: " + e.getMessage());
        }
    }

    private static String sanitise(String filename) {
        if (filename == null || filename.isBlank()) {
            return "attachment";
        }
        String name = filename.replaceAll("[\\\\/]", "_").replaceAll("[:*?\"<>|]", "_");
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
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

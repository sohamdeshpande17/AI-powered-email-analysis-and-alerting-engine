package com.meridian.circular.service;

import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.meridian.circular.config.GraphMailConfig;
import com.meridian.circular.domain.Circular;
import com.meridian.circular.domain.CircularDocument;
import com.meridian.circular.domain.Forwarding;
import com.meridian.circular.dto.Dtos.ForwardRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends forwarding and recall emails via Microsoft Graph API (sendMail).
 *
 * <p>Uses the same Azure AD client-credentials flow as the Python pipeline's
 * {@code o365_reader.py / sender.py}. The HTML email template is a faithful
 * Java port of the rich template from {@code sender.py} — header banner,
 * category badges, urgency colour, AI summary, required action, key entities,
 * and binary document attachments.
 *
 * <p>When {@code app.graph.enabled = false}, all send methods return
 * {@link SendResult#SKIPPED} without making any HTTP calls.
 */
@Service
public class GraphMailService {

    private static final Logger log = LoggerFactory.getLogger(GraphMailService.class);
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String SCOPE = "https://graph.microsoft.com/.default";
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Asia/Kolkata"));
    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final GraphMailConfig config;
    private final NasStorageService nas;
    private final HttpClient httpClient;

    /** Lazily initialised MSAL confidential client. */
    private volatile ConfidentialClientApplication msalApp;

    public GraphMailService(GraphMailConfig config, NasStorageService nas) {
        this.config = config;
        this.nas = nas;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── public API ──────────────────────────────────────────────────────

    /**
     * Send the forwarding email for one team. Attaches all top-level documents.
     *
     * @return result indicating SENT, FAILED, or SKIPPED
     */
    public SendResult sendForwardEmail(Circular circular,
                                       Forwarding forwarding,
                                       String teamName,
                                       List<String> recipientEmails,
                                       List<String> ccEmails,
                                       List<CircularDocument> documents,
                                       ForwardRequest req) {
        if (!config.enabled()) return SendResult.SKIPPED;
        try {
            String html = (req != null && req.finalHtmlBody() != null && !req.finalHtmlBody().isBlank())
                    ? req.finalHtmlBody()
                    : buildForwardHtml(circular, forwarding, teamName);

            String attachmentsJson = buildAttachmentsJson(documents);
            if (req != null && req.customAttachments() != null) {
                String customAttJson = req.customAttachments().stream().map(a -> """
                        {
                          "@odata.type": "#microsoft.graph.fileAttachment",
                          "name": "%s",
                          "contentType": "%s",
                          "contentBytes": "%s"
                        }""".formatted(escJson(a.filename()), escJson(a.mimeType()), escJson(a.contentBase64())))
                        .collect(Collectors.joining(","));
                if (!attachmentsJson.isEmpty() && !customAttJson.isEmpty()) {
                    attachmentsJson += "," + customAttJson;
                } else if (!customAttJson.isEmpty()) {
                    attachmentsJson = customAttJson;
                }
            }

            // Order-preserving + de-duplicated: the caller may pass the same
            // address on both the team recipient list and the ad-hoc customTo.
            java.util.LinkedHashSet<String> allTo = new java.util.LinkedHashSet<>(recipientEmails);
            if (req != null && req.customTo() != null) allTo.addAll(req.customTo());

            String recipientsJson = allTo.stream()
                    .map(e -> """
                            {"emailAddress":{"address":"%s"}}""".formatted(escJson(e)))
                    .collect(Collectors.joining(","));

            // Cc = the team's default Cc recipients (incl. distribution lists)
            // plus any ad-hoc Cc typed in the preview, minus anyone already on
            // the To line. Order-preserving + de-duplicated.
            java.util.LinkedHashSet<String> ccSet = new java.util.LinkedHashSet<>();
            if (ccEmails != null) ccSet.addAll(ccEmails);
            if (req != null && req.customCc() != null) ccSet.addAll(req.customCc());
            ccSet.removeAll(allTo);
            String ccRecipientsJson = ccSet.stream()
                    .map(e -> """
                            {"emailAddress":{"address":"%s"}}""".formatted(escJson(e)))
                    .collect(Collectors.joining(","));

            String payload = """
                    {
                      "message": {
                        "subject": "%s",
                        "body": {"contentType": "HTML", "content": %s},
                        "toRecipients": [%s],
                        "ccRecipients": [%s],
                        "attachments": [%s]
                      },
                      "saveToSentItems": false
                    }""".formatted(
                    escJson(forwarding.emailSubject),
                    jsonString(html),
                    recipientsJson,
                    ccRecipientsJson,
                    attachmentsJson);

            post(payload);
            log.info("Forward email sent to {} for circular {}", allTo, circular.circularNo);
            return SendResult.SENT;
        } catch (Exception e) {
            log.error("Failed to send forward email for circular {}: {}", circular.circularNo, e.getMessage(), e);
            return SendResult.failed(e.getMessage());
        }
    }

    /**
     * Send a recall/retraction notification email to the team.
     */
    public SendResult sendRecallEmail(Circular circular,
                                      Forwarding forwarding,
                                      String teamName,
                                      String recalledByName,
                                      List<String> recipientEmails) {
        if (!config.enabled()) return SendResult.SKIPPED;
        try {
            String html = buildRecallHtml(circular, forwarding, teamName, recalledByName);
            String recipientsJson = recipientEmails.stream()
                    .map(e -> """
                            {"emailAddress":{"address":"%s"}}""".formatted(escJson(e)))
                    .collect(Collectors.joining(","));

            String payload = """
                    {
                      "message": {
                        "subject": "[RECALLED] %s",
                        "body": {"contentType": "HTML", "content": %s},
                        "toRecipients": [%s]
                      },
                      "saveToSentItems": false
                    }""".formatted(
                    escJson(forwarding.emailSubject),
                    jsonString(html),
                    recipientsJson);

            post(payload);
            log.info("Recall email sent to {} for circular {}", recipientEmails, circular.circularNo);
            return SendResult.SENT;
        } catch (Exception e) {
            log.error("Failed to send recall email for circular {}: {}", circular.circularNo, e.getMessage(), e);
            return SendResult.failed(e.getMessage());
        }
    }

    // ── HTML templates ──────────────────────────────────────────────────

    public String buildForwardHtml(Circular circular,
                                    Forwarding forwarding,
                                    String teamName) {
        String urgency = circular.urgency != null
                ? circular.urgency.toUpperCase() : "MEDIUM";
        String urgencyColor = switch (urgency) {
            case "CRITICAL" -> "#dc2626";
            case "HIGH" -> "#ea580c";
            case "MEDIUM" -> "#d97706";
            default -> "#16a34a";
        };
        // Strip background uses a neutral slate tint instead of a pale colour
        // wash. Pale washes look broken under Outlook's forced-dark mode
        // (background gets inverted, the urgency text-on-pale becomes
        // text-on-near-black). A darker neutral reads consistently in both.
        String urgencyBg = "#f1f5f9";

        String categories = circular.categories != null
                ? String.join(", ", circular.categories) : "—";
        String summary = circular.summary != null
                ? circular.summary : "No summary available";
        String action = circular.requiredAction != null
                ? circular.requiredAction : "Manual review required";
        double confidence = circular.confidence != null ? circular.confidence : 0.0;
        String receivedDate = circular.issuedAt != null
                ? DAY_FMT.format(circular.issuedAt) : "—";
        String forwardedDate = forwarding.forwardedAt != null
                ? DATE_FMT.format(forwarding.forwardedAt) : "—";
        String body = forwarding.emailBodySnapshot != null
                ? forwarding.emailBodySnapshot : "—";

        // Build category badges HTML. Solid indigo on white text reads well in
        // both light and forced-dark modes (Outlook ignores @media queries),
        // unlike the previous pastel-on-dark-text combo.
        String catBadges = "";
        if (circular.categories != null) {
            catBadges = circular.categories.stream()
                    .map(c -> """
                            <span style="display:inline-block;background:#4f46e5;color:#ffffff;\
                            padding:3px 10px;border-radius:12px;font-size:12px;font-weight:600;\
                            margin-right:6px;">%s</span>""".formatted(esc(c)))
                    .collect(Collectors.joining());
        }

        // Entity chips: solid mid-slate fill so they survive Outlook dark mode
        // without the @media query firing.
        String entityChips = "";
        if (circular.keyEntities != null) {
            entityChips = circular.keyEntities.stream()
                    .map(e -> """
                            <span class="entity-chip" style="display:inline-block;background:#475569;color:#f1f5f9;\
                            padding:3px 10px;border-radius:12px;font-size:12px;margin-right:6px;\
                            margin-bottom:4px;">%s</span>""".formatted(esc(e)))
                    .collect(Collectors.joining());
        }

        return """
                <html>
                <head>
                  <meta name="color-scheme" content="light dark">
                  <meta name="supported-color-schemes" content="light dark">
                  <style>
                    /* Outlook desktop strips most of this; we still keep it
                       for Apple Mail / Gmail / Outlook web which honour
                       @media. The inline styles below are chosen to remain
                       legible under forced-dark inversion (Outlook), which
                       is the main reason past renders looked broken. */
                    :root { color-scheme: light dark; }
                    body { font-family:'Segoe UI',Arial,sans-serif;line-height:1.6;color:#334155;max-width:800px;margin:0 auto;padding:0; }
                    .main-bg { background:#ffffff; }
                    .card-bg { background:#f8fafc; border: 1px solid #e2e8f0; }
                    .muted-text { color:#475569; }
                    .title-text { color:#1e293b; }
                    .border-line { border-bottom:2px solid #e2e8f0; }
                    .footer-bg { background:#f1f5f9; color:#64748b; }
                    .entity-chip { background:#475569; color:#f1f5f9; }
                    @media (prefers-color-scheme: dark) {
                      body, .main-bg { background-color: #0f172a !important; color: #e2e8f0 !important; }
                      .card-bg { background-color: #1e293b !important; border-color: #334155 !important; }
                      .muted-text { color: #cbd5e1 !important; }
                      .title-text { color: #f8fafc !important; }
                      .border-line { border-color: #334155 !important; }
                      .footer-bg { background-color: #020617 !important; color: #94a3b8 !important; }
                      .entity-chip { background-color: #334155 !important; color: #f8fafc !important; }
                    }
                  </style>
                </head>
                <body>
                  <!-- Header Banner -->
                  <div style="background:linear-gradient(135deg,#1e40af 0%%,#3b82f6 50%%,#6366f1 100%%);padding:24px 30px;border-radius:8px 8px 0 0;">
                    <h2 style="color:#ffffff;margin:0 0 4px 0;font-size:20px;">📋 Circular Forwarded — %s</h2>
                    <div style="color:#bfdbfe;font-size:13px;">Forwarded to <strong style="color:#fff;">%s</strong> on %s</div>
                  </div>

                  <!-- Urgency + Category Strip -->
                  <div style="background:%s;padding:14px 30px;border-left:4px solid %s;display:flex;align-items:center;gap:16px;flex-wrap:wrap;">
                    <span style="font-weight:700;color:%s;font-size:14px;text-transform:uppercase;letter-spacing:0.5px;">⚡ %s</span>
                    <span style="color:#64748b;font-size:13px;">|</span>
                    %s
                  </div>

                  <div class="main-bg" style="padding:24px 30px;">
                    <!-- Original Email Info -->
                    <div class="card-bg" style="padding:14px 18px;border-left:4px solid #3b82f6;margin-bottom:20px;border-radius:0 6px 6px 0;">
                      <h3 style="margin:0 0 8px 0;color:#3b82f6;font-size:14px;">📧 ORIGINAL CIRCULAR</h3>
                      <div class="muted-text" style="font-size:13px;">
                        <strong>Subject:</strong> %s<br>
                        <strong>Source:</strong> %s<br>
                        <strong>Reference:</strong> %s<br>
                        <strong>Received:</strong> %s
                      </div>
                    </div>

                    <!-- Analysis Section -->
                    <div class="card-bg" style="padding:14px 18px;border-left:4px solid #10b981;margin-bottom:20px;border-radius:0 6px 6px 0;">
                      <h3 style="margin:0 0 8px 0;color:#10b981;font-size:14px;">📊 AI ANALYSIS</h3>
                      <div class="muted-text" style="font-size:13px;">
                        <strong>Categories:</strong> %s<br>
                        <strong>Urgency:</strong> <span style="font-weight:700;color:%s;">%s</span><br>
                        <strong>Confidence:</strong> %s%%
                      </div>
                    </div>

                    <!-- Summary -->
                    <h4 class="title-text border-line" style="padding-bottom:6px;font-size:14px;margin:20px 0 10px;">📝 Summary</h4>
                    <div class="muted-text" style="font-size:14px;margin-bottom:20px;line-height:1.7;">%s</div>

                    <!-- Required Action -->
                    <h4 class="title-text border-line" style="padding-bottom:6px;font-size:14px;margin:20px 0 10px;">➜ Required Action</h4>
                    <div class="muted-text" style="font-size:14px;margin-bottom:20px;line-height:1.7;">%s</div>

                    <!-- Key Entities -->
                    <h4 class="title-text border-line" style="padding-bottom:6px;font-size:14px;margin:20px 0 10px;">🏷️ Key Entities</h4>
                    <div style="margin-bottom:20px;">%s</div>
                  </div>

                  <!-- Footer -->
                  <div class="footer-bg" style="padding:14px 30px;border-radius:0 0 8px 8px;text-align:center;">
                    <div style="font-size:11px;">
                      This email was sent by the Circular Analyser on behalf of Compliance · %s
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                esc(urgency),                           // header urgency
                esc(teamName),                          // header team
                forwardedDate,                          // header date
                urgencyBg,                              // strip bg
                urgencyColor,                           // strip border
                urgencyColor,                           // strip text color
                esc(urgency),                           // strip urgency label
                catBadges,                              // strip category badges
                esc(circular.subject),                  // original subject
                esc(circular.sourceName),               // source name
                esc(circular.circularNo),               // reference
                receivedDate,                           // received date
                esc(categories),                        // analysis categories
                urgencyColor,                           // analysis urgency color
                esc(urgency),                           // analysis urgency label
                String.valueOf(Math.round(confidence * 100)), // confidence
                formatAsBulletList(summary),            // summary
                formatAsBulletList(action),             // required action
                entityChips.isEmpty() ? "<span style=\"font-size:13px;color:#94a3b8;\">None identified</span>" : entityChips,
                forwardedDate                           // footer date
        );
    }

    private String formatAsBulletList(String text) {
        if (text == null || text.isBlank()) return "—";
        String[] lines = text.split("\\r?\\n");
        StringBuilder sb = new StringBuilder("<ul style=\"margin:0;padding-left:24px;list-style-type:disc;\">");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) trimmed = trimmed.substring(2).trim();
            else if (trimmed.startsWith("* ")) trimmed = trimmed.substring(2).trim();
            else if (trimmed.startsWith("• ")) trimmed = trimmed.substring(2).trim();
            if (!trimmed.isEmpty()) {
                sb.append("<li style=\"margin-bottom:12px;padding-left:4px;\">").append(esc(trimmed)).append("</li>");
            }
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private String buildRecallHtml(Circular circular,
                                   Forwarding forwarding,
                                   String teamName,
                                   String recalledByName) {
        String forwardedDate = forwarding.forwardedAt != null
                ? DATE_FMT.format(forwarding.forwardedAt) : "—";

        return """
                <html>
                <body style="font-family:'Segoe UI',Arial,sans-serif;line-height:1.6;color:#333;max-width:800px;margin:0 auto;padding:0;">
                  <!-- Header Banner -->
                  <div style="background:linear-gradient(135deg,#dc2626 0%%,#ef4444 50%%,#f87171 100%%);padding:24px 30px;border-radius:8px 8px 0 0;">
                    <h2 style="color:#ffffff;margin:0 0 4px 0;font-size:20px;">🔄 Circular Forwarding Recalled</h2>
                    <div style="color:#fecaca;font-size:13px;">The forwarding to <strong style="color:#fff;">%s</strong> has been recalled</div>
                  </div>

                  <div style="padding:24px 30px;background:#ffffff;">
                    <div style="background:#fef2f2;padding:16px 20px;border-left:4px solid #dc2626;border-radius:0 6px 6px 0;margin-bottom:20px;">
                      <p style="margin:0;font-size:14px;color:#991b1b;">
                        <strong>%s</strong> has recalled the forwarding of the circular below.
                        Please disregard the previously forwarded circular email.
                      </p>
                    </div>

                    <div style="background:#f8fafc;padding:14px 18px;border-radius:6px;margin-bottom:16px;">
                      <div style="font-size:13px;color:#475569;">
                        <strong>Subject:</strong> %s<br>
                        <strong>Reference:</strong> %s<br>
                        <strong>Originally forwarded:</strong> %s<br>
                        <strong>Forwarded to:</strong> %s
                      </div>
                    </div>
                  </div>

                  <!-- Footer -->
                  <div style="background:#f1f5f9;padding:14px 30px;border-radius:0 0 8px 8px;text-align:center;">
                    <div style="font-size:11px;color:#94a3b8;">
                      This is an automated recall notification from the Circular Analyser.
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                esc(teamName),
                esc(recalledByName),
                esc(circular.subject),
                esc(circular.circularNo),
                forwardedDate,
                esc(teamName)
        );
    }

    // ── Graph API calls ─────────────────────────────────────────────────

    private void post(String jsonBody) throws Exception {
        String token = acquireToken();
        String url = GRAPH_BASE + "/users/" + config.senderEmail() + "/sendMail";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Graph API returned HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    private String acquireToken() throws Exception {
        if (msalApp == null) {
            synchronized (this) {
                if (msalApp == null) {
                    if (config.clientSecret() == null || config.clientSecret().isBlank()) {
                        throw new RuntimeException("O365 clientSecret is null or empty in application configuration.");
                    }
                    msalApp = ConfidentialClientApplication.builder(
                                    config.clientId(),
                                    ClientCredentialFactory.createFromSecret(config.clientSecret()))
                            .authority("https://login.microsoftonline.com/" + config.tenantId())
                            .build();
                }
            }
        }
        IAuthenticationResult result = msalApp
                .acquireToken(ClientCredentialParameters.builder(Collections.singleton(SCOPE)).build())
                .get();
        return result.accessToken();
    }

    // ── Attachment helpers ───────────────────────────────────────────────

    private String buildAttachmentsJson(List<CircularDocument> documents) {
        if (documents == null || documents.isEmpty()) return "";
        return documents.stream()
                .filter(d -> d.parentDocumentId == null) // top-level only
                .map(d -> {
                    try {
                        byte[] content = nas.read(d.nasRelativePath);
                        String b64 = Base64.getEncoder().encodeToString(content);
                        return """
                                {
                                  "@odata.type": "#microsoft.graph.fileAttachment",
                                  "name": "%s",
                                  "contentType": "%s",
                                  "contentBytes": "%s"
                                }""".formatted(
                                escJson(d.originalFilename),
                                escJson(d.mimeType != null ? d.mimeType : "application/octet-stream"),
                                b64);
                    } catch (Exception e) {
                        log.warn("Skipping attachment {} — could not read from NAS: {}",
                                d.originalFilename, e.getMessage());
                        return null;
                    }
                })
                .filter(s -> s != null)
                .collect(Collectors.joining(","));
    }

    // ── JSON / HTML escaping ────────────────────────────────────────────

    /** Escape for JSON string values. */
    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** Wrap a string as a JSON string literal (with quotes). */
    private static String jsonString(String s) {
        return "\"" + escJson(s) + "\"";
    }

    /** Basic HTML entity escaping. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ── Result type ─────────────────────────────────────────────────────

    public sealed interface SendResult {
        SendResult SENT = new Sent();
        SendResult SKIPPED = new Skipped();

        static SendResult failed(String message) {
            return new Failed(message);
        }

        record Sent() implements SendResult {}
        record Skipped() implements SendResult {}
        record Failed(String message) implements SendResult {}
    }
}

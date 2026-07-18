package com.meridian.circular.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.meridian.circular.config.GraphMailConfig;
import com.meridian.circular.dto.Dtos.DirectoryRecipient;
import com.meridian.circular.dto.Dtos.DirectoryUser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Microsoft Graph directory search (BRD FR-USER-03) — looks up Entra ID users
 * by name or email so an Admin / Compliance Head can provision them.
 *
 * <p>Reuses the same {@code app.graph.*} client-credentials registration as
 * {@link GraphMailService}. The app must additionally hold the
 * {@code User.Read.All} application permission for {@code GET /users} to
 * succeed; without it Graph returns 403 and the caller falls back to the
 * built-in sample directory ({@link UserService}).
 */
@Service
public class GraphDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(GraphDirectoryService.class);
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String SCOPE = "https://graph.microsoft.com/.default";

    private final GraphMailConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Lazily initialised MSAL confidential client (shares the mail app's creds). */
    private volatile ConfidentialClientApplication msalApp;

    public GraphDirectoryService(GraphMailConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Whether Graph is configured/enabled; when false the caller uses the mock. */
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Search Entra ID for users whose name or email matches {@code query}.
     * Throws on any Graph failure so the caller can fall back to the sample
     * directory rather than surfacing an error to the provisioning UI.
     */
    public List<DirectoryUser> search(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        try {
            String token = acquireToken();
            // $search needs the ConsistencyLevel: eventual header; it matches
            // substrings across the listed properties (unlike startswith).
            String searchExpr = "\"displayName:" + q + "\" OR \"mail:" + q
                    + "\" OR \"userPrincipalName:" + q + "\"";
            String url = GRAPH_BASE + "/users"
                    + "?$select=id,displayName,mail,userPrincipalName"
                    + "&$top=15"
                    + "&$search=" + URLEncoder.encode(searchExpr, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("ConsistencyLevel", "eventual")
                    .GET()
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException(
                        "Graph /users returned HTTP " + resp.statusCode() + ": " + resp.body());
            }
            return parse(resp.body());
        } catch (Exception e) {
            // Bubble up so UserService can fall back to the sample directory.
            throw new RuntimeException("Graph directory search failed: " + e.getMessage(), e);
        }
    }

    /**
     * Search Entra ID for forward Cc candidates — both individual users AND
     * mail-enabled groups / distribution lists ('CC users of DL'). Each hit is
     * tagged USER or GROUP so the UI can show what kind of address it is.
     */
    public List<DirectoryRecipient> searchRecipients(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        try {
            String token = acquireToken();
            List<DirectoryRecipient> out = new ArrayList<>();

            // People
            String userSearch = "\"displayName:" + q + "\" OR \"mail:" + q
                    + "\" OR \"userPrincipalName:" + q + "\"";
            String userUrl = GRAPH_BASE + "/users"
                    + "?$select=id,displayName,mail,userPrincipalName"
                    + "&$top=10"
                    + "&$search=" + URLEncoder.encode(userSearch, StandardCharsets.UTF_8);
            for (JsonNode u : get(userUrl, token).path("value")) {
                String mail = textOrNull(u, "mail");
                if (mail == null) mail = textOrNull(u, "userPrincipalName");
                String name = textOrNull(u, "displayName");
                if (name != null && mail != null) {
                    out.add(new DirectoryRecipient(textOrNull(u, "id"), name, mail, "USER"));
                }
            }

            // Distribution lists / groups (mail-enabled only — they must have
            // an address to be a usable Cc target).
            String groupSearch = "\"displayName:" + q + "\" OR \"mail:" + q + "\"";
            String groupUrl = GRAPH_BASE + "/groups"
                    + "?$select=id,displayName,mail"
                    + "&$top=10"
                    + "&$search=" + URLEncoder.encode(groupSearch, StandardCharsets.UTF_8);
            for (JsonNode g : get(groupUrl, token).path("value")) {
                String mail = textOrNull(g, "mail");
                String name = textOrNull(g, "displayName");
                if (name != null && mail != null) {
                    out.add(new DirectoryRecipient(textOrNull(g, "id"), name, mail, "GROUP"));
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Graph recipient search failed: " + e.getMessage(), e);
        }
    }

    private JsonNode get(String url, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("ConsistencyLevel", "eventual")
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Graph returned HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readTree(resp.body());
    }

    private List<DirectoryUser> parse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        List<DirectoryUser> out = new ArrayList<>();
        for (JsonNode u : root.path("value")) {
            String id = textOrNull(u, "id");
            String name = textOrNull(u, "displayName");
            // mail is null for users without a mailbox — fall back to UPN.
            String mail = textOrNull(u, "mail");
            if (mail == null) {
                mail = textOrNull(u, "userPrincipalName");
            }
            if (name != null && mail != null) {
                out.add(new DirectoryUser(id, name, mail));
            }
        }
        return out;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        return s.isBlank() ? null : s;
    }

    private String acquireToken() throws Exception {
        if (msalApp == null) {
            synchronized (this) {
                if (msalApp == null) {
                    if (config.clientSecret() == null || config.clientSecret().isBlank()) {
                        throw new RuntimeException(
                                "Graph clientSecret is null or empty in application configuration.");
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
}

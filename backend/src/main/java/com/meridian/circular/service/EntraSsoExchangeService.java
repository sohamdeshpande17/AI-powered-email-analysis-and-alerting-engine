package com.meridian.circular.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.circular.config.SsoConfig;
import com.meridian.circular.web.ApiException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Server-side Microsoft Entra ID authorization-code redemption — the
 * confidential-client half of the SSO sign-in (FR-AUTH).
 *
 * <p>The SPA sends the browser to Entra ({@code response_type=code}); Entra
 * redirects back to the UI with a single-use {@code code}; the UI forwards that
 * code here ({@code POST /api/auth/sso/exchange}). This service exchanges it at
 * the tenant token endpoint using the app's <b>client secret</b> (which never
 * leaves the backend) and PKCE {@code code_verifier}, and returns the resulting
 * tokens:
 * <ul>
 *   <li><b>id_token</b> — audience = this app; {@link SsoTokenValidator}
 *       validates it to identify the user.</li>
 *   <li><b>access_token</b> — audience = Microsoft Graph; handed back to the UI
 *       for the Graph-backed search feature.</li>
 * </ul>
 *
 * <p>This is why the app registration is the <b>"Web"</b> (confidential-client)
 * platform: the code is redeemed server-to-server with a secret, not in the
 * browser.
 */
@Service
public class EntraSsoExchangeService {

    private static final Logger log = LoggerFactory.getLogger(EntraSsoExchangeService.class);

    private final SsoConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public EntraSsoExchangeService(SsoConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** The tokens returned by a successful code redemption. */
    public record TokenSet(String idToken, String accessToken, long expiresInSeconds) {}

    /**
     * Redeem an authorization code for tokens. {@code codeVerifier} is the PKCE
     * verifier the SPA generated for this sign-in (may be null if PKCE was not
     * used). Throws {@link ApiException} (401) if Entra rejects the redemption.
     */
    public TokenSet redeem(String code, String redirectUri, String codeVerifier) {
        if (config.clientSecret() == null || config.clientSecret().isBlank()) {
            log.error("SSO code redemption attempted but app.sso.client-secret is not configured");
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Microsoft sign-in is not configured on the server.");
        }
        // The redirect_uri here MUST byte-for-byte match the one used in the
        // authorize request (Entra binds the code to it).
        String effectiveRedirect = redirectUri != null && !redirectUri.isBlank()
                ? redirectUri
                : config.audience(); // never reached in practice; keeps it non-null

        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", config.clientId());
        form.put("client_secret", config.clientSecret());
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", effectiveRedirect);
        form.put("scope", config.scopes());
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            form.put("code_verifier", codeVerifier);
        }

        String tokenUri = "https://login.microsoftonline.com/" + config.tenantId()
                + "/oauth2/v2.0/token";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUri))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(urlEncode(form)))
                    .build();

            HttpResponse<String> resp =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode body = mapper.readTree(resp.body());
            if (resp.statusCode() >= 400) {
                // Surface the Entra error code (e.g. AADSTS70008 expired code,
                // AADSTS9002327, AADSTS501491) to the server log; keep the
                // client-facing message generic.
                String aad = body.path("error").asText("")
                        + " — " + body.path("error_description").asText("");
                log.warn("Entra token redemption failed (HTTP {}): {}", resp.statusCode(), aad);
                throw new ApiException(HttpStatus.UNAUTHORIZED,
                        "Microsoft sign-in could not be completed. Please try again.");
            }

            String idToken = textOrNull(body, "id_token");
            String accessToken = textOrNull(body, "access_token");
            long expiresIn = body.path("expires_in").asLong(0);
            if (idToken == null) {
                log.warn("Entra token response had no id_token; scopes={}", config.scopes());
                throw new ApiException(HttpStatus.UNAUTHORIZED,
                        "Microsoft sign-in did not return an identity token.");
            }
            return new TokenSet(idToken, accessToken, expiresIn);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Entra token redemption errored: {}", e.getMessage());
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "Microsoft sign-in could not be completed. Please try again.");
        }
    }

    private static String urlEncode(Map<String, String> form) {
        StringJoiner sj = new StringJoiner("&");
        for (Map.Entry<String, String> e : form.entrySet()) {
            sj.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sj.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        return s.isBlank() ? null : s;
    }
}

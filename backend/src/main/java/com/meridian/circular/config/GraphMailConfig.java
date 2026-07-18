package com.meridian.circular.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code app.graph.*} in application.yml.
 * <p>
 * Holds the Azure AD / Microsoft Graph credentials used by
 * {@link com.meridian.circular.service.GraphMailService} to send
 * forwarding and recall emails via the O365 Graph sendMail endpoint.
 */
@ConfigurationProperties(prefix = "app.graph")
public record GraphMailConfig(
        String tenantId,
        String clientId,
        String clientSecret,
        String senderEmail,
        boolean enabled
) {}

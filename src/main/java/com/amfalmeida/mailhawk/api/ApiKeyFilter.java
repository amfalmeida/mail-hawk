package com.amfalmeida.mailhawk.api;

import com.amfalmeida.mailhawk.config.AppConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Checks the {@code X-API-Key} header against {@code app.api-key} on resources annotated with
 * {@link ApiKeyRequired}. Those resources answer 403 until {@code app.api-enabled} is true and
 * {@code app.api-key} is set, so the API can't be turned on without a key.
 * Application-scoped so it is created lazily; providers are otherwise built during static init, before
 * {@link AppConfig} can be injected.
 */
@Slf4j
@Provider
@ApplicationScoped
@ApiKeyRequired
@Priority(Priorities.AUTHENTICATION)
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class ApiKeyFilter implements ContainerRequestFilter {

    static final String API_KEY_HEADER = "X-API-Key";

    private final AppConfig appConfig;

    @Override
    public void filter(final ContainerRequestContext requestContext) {
        if (!appConfig.apiEnabled()) {
            requestContext.abortWith(error(Response.Status.FORBIDDEN, "API disabled: set app.api-enabled=true"));
            return;
        }
        final String expected = appConfig.apiKey();
        if (expected == null || expected.isBlank()) {
            log.warn("API is enabled but app.api-key is not set; refusing request");
            requestContext.abortWith(error(Response.Status.FORBIDDEN, "API key not configured: set app.api-key"));
            return;
        }
        final String apiKey = requestContext.getHeaderString(API_KEY_HEADER);
        if (apiKey == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected {} {}: missing or invalid {} header",
                requestContext.getMethod(), requestContext.getUriInfo().getPath(), API_KEY_HEADER);
            requestContext.abortWith(
                error(Response.Status.UNAUTHORIZED, "Missing or invalid " + API_KEY_HEADER + " header"));
        }
    }

    private static Response error(final Response.Status status, final String message) {
        return Response.status(status).entity(Map.of("error", message)).type(MediaType.APPLICATION_JSON).build();
    }
}

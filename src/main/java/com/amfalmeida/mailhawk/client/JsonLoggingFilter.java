package com.amfalmeida.mailhawk.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

@Provider
public class JsonLoggingFilter implements ClientRequestFilter {

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void filter(final ClientRequestContext requestContext) throws IOException {
        final Object entity = requestContext.getEntity();
        if (entity != null) {
            final String json = objectMapper.writeValueAsString(entity);
            Log.infof("REST Client Request Body: %s", json);

            // Set headers
            final MultivaluedMap<String, Object> headers = requestContext.getHeaders();
            headers.putSingle("Accept", "application/json");
            headers.putSingle("Content-Type", "application/json");

            // Replace entity with properly serialized JSON string
            requestContext.setEntity(json, null, jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE);
            requestContext.getHeaders().putSingle("Content-Type", "application/json");
        }
    }
}

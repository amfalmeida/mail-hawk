package com.amfalmeida.mailhawk.api;

import com.amfalmeida.mailhawk.config.AppConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyFilterTest {

    private static final String KEY = "secret";

    private AppConfig appConfig;
    private ApiKeyFilter filter;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class);
        when(appConfig.apiEnabled()).thenReturn(true);
        when(appConfig.apiKey()).thenReturn(KEY);
        filter = new ApiKeyFilter(appConfig);
    }

    @Test
    void letsRequestWithValidKeyThrough() {
        final ContainerRequestContext context = request(KEY);

        filter.filter(context);

        verify(context, never()).abortWith(any());
    }

    @Test
    void rejectsMissingOrWrongKey() {
        assertEquals(401, abortStatus(request(null)));
        assertEquals(401, abortStatus(request("nope")));
        assertEquals(401, abortStatus(request("")));
    }

    @Test
    void rejectsEverythingWhenApiDisabled() {
        when(appConfig.apiEnabled()).thenReturn(false);

        assertEquals(403, abortStatus(request(KEY)));
    }

    @Test
    void rejectsEverythingWhenEnabledWithoutApiKey() {
        when(appConfig.apiKey()).thenReturn("");

        assertEquals(403, abortStatus(request(KEY)));
        assertEquals(403, abortStatus(request("")));
    }

    private int abortStatus(final ContainerRequestContext context) {
        filter.filter(context);
        final ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(context).abortWith(captor.capture());
        return captor.getValue().getStatus();
    }

    private static ContainerRequestContext request(final String apiKey) {
        final ContainerRequestContext context = mock(ContainerRequestContext.class);
        final UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("/api/invoices");
        when(context.getMethod()).thenReturn("POST");
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(context.getHeaderString(ApiKeyFilter.API_KEY_HEADER)).thenReturn(apiKey);
        return context;
    }
}

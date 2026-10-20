package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Processed Invoice Cache Tests")
class ProcessedInvoiceCacheTest {

    private ProcessedInvoiceCache cache;

    @BeforeEach
    void setUp() {
        final AppConfig appConfig = mock(AppConfig.class);
        when(appConfig.processedInvoiceCacheHours()).thenReturn(24);
        when(appConfig.processedInvoiceCacheSize()).thenReturn(100L);
        cache = new ProcessedInvoiceCache(appConfig);
        cache.initCache();
    }

    @Test
    @DisplayName("Should report unknown invoice as not processed")
    void shouldReportUnknownInvoiceAsNotProcessed() {
        assertFalse(cache.isProcessed("A:123*B:456"));
    }

    @Test
    @DisplayName("Should report marked invoice as processed")
    void shouldReportMarkedInvoiceAsProcessed() {
        cache.markProcessed("A:123*B:456");

        assertTrue(cache.isProcessed("A:123*B:456"));
        assertFalse(cache.isProcessed("A:123*B:789"));
    }
}

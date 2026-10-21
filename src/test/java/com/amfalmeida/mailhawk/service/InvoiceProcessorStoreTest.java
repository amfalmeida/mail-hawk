package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.AppConfig;
import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.email.model.Invoice;
import com.amfalmeida.mailhawk.email.model.InvoiceContent;
import com.amfalmeida.mailhawk.email.model.InvoiceResult;
import com.amfalmeida.mailhawk.email.model.InvoiceType;
import com.amfalmeida.mailhawk.email.model.SheetsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InvoiceProcessor.storeInvoice Tests")
class InvoiceProcessorStoreTest {

    private SheetsService sheetsService;
    private ActualBudgetService actualBudgetService;
    private InvoiceProcessor invoiceProcessor;

    @BeforeEach
    void setUp() {
        sheetsService = mock(SheetsService.class);
        actualBudgetService = mock(ActualBudgetService.class);
        when(sheetsService.getConfigurationByNif(anyString())).thenReturn(Optional.empty());
        when(sheetsService.getConfigurationByEmail(anyString())).thenReturn(Optional.empty());

        final AppConfig appConfig = mock(AppConfig.class);
        when(appConfig.processedInvoiceCacheHours()).thenReturn(24);
        when(appConfig.processedInvoiceCacheSize()).thenReturn(100L);
        when(appConfig.defaultInvoiceType()).thenReturn("other");
        final ProcessedInvoiceCache cache = new ProcessedInvoiceCache(appConfig);
        cache.initCache();

        invoiceProcessor = new InvoiceProcessor(sheetsService, cache, new QrCodeParser(), appConfig,
            mock(MailConfig.class), actualBudgetService);
    }

    @Test
    @DisplayName("Appends, imports into Actual and skips the same invoice afterwards")
    void appendsThenSkipsAlreadyProcessed() {
        when(sheetsService.addInvoice(any())).thenReturn(SheetsResult.appended("ok"));

        final InvoiceResult first = invoiceProcessor.storeInvoice(invoice());
        final InvoiceResult second = invoiceProcessor.storeInvoice(invoice());

        assertEquals(InvoiceResult.Status.APPENDED, first.status());
        assertEquals("other", first.invoiceType());
        assertEquals(InvoiceResult.Status.ALREADY_PROCESSED, second.status());
        verify(sheetsService, times(1)).addInvoice(any());
        verify(actualBudgetService, times(1)).importInvoices(anyList());
    }

    @Test
    @DisplayName("Uses the type configured for the issuer NIF")
    void resolvesTypeByNif() {
        when(sheetsService.getConfigurationByNif("123456789"))
            .thenReturn(Optional.of(new InvoiceType("water", "", "Water Co", "123456789")));
        when(sheetsService.addInvoice(any())).thenReturn(SheetsResult.appended("ok"));

        assertEquals("water", invoiceProcessor.storeInvoice(invoice()).invoiceType());
    }

    @Test
    @DisplayName("Does not import or mark processed when the Sheets write fails")
    void sheetsErrorIsRetried() {
        when(sheetsService.addInvoice(any())).thenReturn(SheetsResult.error("quota"));

        final InvoiceResult result = invoiceProcessor.storeInvoice(invoice());

        assertEquals(InvoiceResult.Status.ERROR, result.status());
        assertEquals("quota", result.message());
        verify(actualBudgetService, never()).importInvoices(anyList());
        assertEquals(InvoiceResult.Status.ERROR, invoiceProcessor.storeInvoice(invoice()).status());
    }

    @Test
    @DisplayName("Entity name replaces the resolved type's name but keeps its type")
    void entityNameOverridesName() {
        when(sheetsService.getConfigurationByNif("123456789"))
            .thenReturn(Optional.of(new InvoiceType("water", "", "Water Co", "123456789")));
        when(sheetsService.addInvoice(any())).thenReturn(SheetsResult.appended("ok"));
        final Invoice invoice = invoice();

        final InvoiceResult result = invoiceProcessor.storeInvoice(invoice, " Águas do Porto ");

        assertEquals("water", result.invoiceType());
        assertEquals("Águas do Porto", invoice.getInvoiceType().name());
    }

    private static Invoice invoice() {
        return Invoice.builder()
            .fromAddress("billing@company.pt")
            .invoiceContent(InvoiceContent.builder()
                .invoiceId("FT 1/1")
                .issuerTin("123456789")
                .total(new BigDecimal("12.30"))
                .raw("API:123456789:FT 1/1")
                .build())
            .build();
    }
}

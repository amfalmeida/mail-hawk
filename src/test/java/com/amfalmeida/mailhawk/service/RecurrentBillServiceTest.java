package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.AppConfig;
import com.amfalmeida.mailhawk.email.model.RecurrentBill;
import com.amfalmeida.mailhawk.email.model.SheetsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Recurrent Bill Service Tests")
class RecurrentBillServiceTest {

    private SheetsService sheetsService;
    private ActualBudgetService actualBudgetService;
    private RecurrentBillService recurrentBillService;

    @BeforeEach
    void setUp() {
        sheetsService = mock(SheetsService.class);
        actualBudgetService = mock(ActualBudgetService.class);

        final AppConfig appConfig = mock(AppConfig.class);
        when(appConfig.processedInvoiceCacheHours()).thenReturn(24);
        when(appConfig.processedInvoiceCacheSize()).thenReturn(100L);
        final ProcessedInvoiceCache cache = new ProcessedInvoiceCache(appConfig);
        cache.initCache();

        recurrentBillService = new RecurrentBillService(sheetsService, actualBudgetService, cache);

        final RecurrentBill dueToday = new RecurrentBill("rent", "", "landlord@example.com", "Landlord",
            "123456789", "987654321", new BigDecimal("500.00"), null, LocalDate.now().getDayOfMonth(), "", "");
        when(sheetsService.getRecurrentBills()).thenReturn(List.of(dueToday));
    }

    @Test
    @DisplayName("Should not import into Actual Budget or mark processed when Sheets fails")
    void shouldRetryWhenSheetsFails() {
        when(sheetsService.addInvoice(any()))
            .thenReturn(SheetsResult.error("quota"))
            .thenReturn(SheetsResult.appended("ok"));

        recurrentBillService.processRecurrentBills();
        verify(actualBudgetService, never()).importInvoices(any());

        recurrentBillService.processRecurrentBills();
        verify(sheetsService, times(2)).addInvoice(any());
        verify(actualBudgetService, times(1)).importInvoices(any());
    }

    @Test
    @DisplayName("Should process a due bill only once")
    void shouldProcessDueBillOnce() {
        when(sheetsService.addInvoice(any())).thenReturn(SheetsResult.appended("ok"));

        recurrentBillService.processRecurrentBills();
        recurrentBillService.processRecurrentBills();

        verify(sheetsService, times(1)).addInvoice(any());
        verify(actualBudgetService, times(1)).importInvoices(any());
    }
}

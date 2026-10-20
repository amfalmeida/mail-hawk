package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.client.ActualBudgetClient;
import com.amfalmeida.mailhawk.client.dto.TransactionImportRequest;
import com.amfalmeida.mailhawk.config.ActualConfig;
import com.amfalmeida.mailhawk.email.model.Invoice;
import com.amfalmeida.mailhawk.email.model.InvoiceContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Actual Budget Service Tests")
class ActualBudgetServiceTest {

    private ActualBudgetClient client;
    private ActualBudgetService service;

    @BeforeEach
    void setUp() {
        final ActualConfig config = mock(ActualConfig.class);
        when(config.enabled()).thenReturn(Optional.of(true));
        when(config.budgetSyncId()).thenReturn(Optional.of("budget"));
        when(config.accountId()).thenReturn(Optional.of("account"));
        when(config.apiKey()).thenReturn(Optional.of("key"));

        client = mock(ActualBudgetClient.class);
        service = new ActualBudgetService(config);
        service.client = client;
    }

    @ParameterizedTest(name = "atcud=''{0}'' -> imported_id=''{1}''")
    @DisplayName("Should use ATCUD as imported_id, falling back to the raw QR when missing or '0'")
    @CsvSource(value = {
        "ABCD-123, ABCD-123",
        "0, A:123*H:0",
        "NULL, A:123*H:0",
        "'  ', A:123*H:0"
    }, nullValues = "NULL")
    void shouldPickImportedId(final String atcud, final String expected) {
        final InvoiceContent content = InvoiceContent.builder()
            .atcud(atcud)
            .raw("A:123*H:0")
            .total(new BigDecimal("10.00"))
            .build();
        final Invoice invoice = Invoice.builder().invoiceContent(content).filename("f.pdf").build();

        service.importInvoices(List.of(invoice));

        final ArgumentCaptor<TransactionImportRequest> captor = ArgumentCaptor.forClass(TransactionImportRequest.class);
        verify(client).importTransactions(anyString(), anyString(), anyString(), captor.capture());
        assertEquals(expected, captor.getValue().transactions().get(0).importedId());
    }
}

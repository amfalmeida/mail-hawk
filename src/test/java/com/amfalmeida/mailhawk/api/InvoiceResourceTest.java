package com.amfalmeida.mailhawk.api;

import com.amfalmeida.mailhawk.email.model.Invoice;
import com.amfalmeida.mailhawk.email.model.InvoiceContent;
import com.amfalmeida.mailhawk.email.model.InvoiceResult;
import com.amfalmeida.mailhawk.service.InvoiceProcessor;
import com.amfalmeida.mailhawk.service.QrCodeParser;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvoiceResourceTest {

    private static final String RAW = "A:123456789*B:999999990*C:PT*D:FT*E:N*F:20261001*G:FT 2026/123"
        + "*H:ABCD1234-123*I1:PT*I7:10.00*I8:2.30*N:2.30*O:12.30*Q:abcd*R:1234";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 2);

    private InvoiceProcessor invoiceProcessor;
    private InvoiceResource resource;

    @BeforeEach
    void setUp() {
        invoiceProcessor = mock(InvoiceProcessor.class);
        resource = new InvoiceResource(invoiceProcessor, new QrCodeParser());
    }

    @Test
    void rejectsMissingFieldsAndInvalidRaw() {
        assertEquals(400, resource.create(null).getStatus());
        assertEquals(400, resource.create(request(" ")).getStatus());
        assertEquals(400, resource.create(new InvoiceRequest(null, "Water Co", "Fatura", RAW)).getStatus());
        assertEquals(400, resource.create(request("not a qr code")).getStatus());
        assertEquals(400, resource.create(request("A:123456789*O:12.30")).getStatus());
        verify(invoiceProcessor, never()).storeInvoice(any(), any());
    }

    @Test
    void parsesInvoiceDataFromRaw() {
        when(invoiceProcessor.storeInvoice(any(), anyString())).thenReturn(result(InvoiceResult.Status.APPENDED));

        final Response response = resource.create(request(RAW));

        assertEquals(201, response.getStatus());
        final Invoice invoice = captureInvoice();
        final InvoiceContent content = invoice.getInvoiceContent();
        assertEquals("123456789", content.getIssuerTin());
        assertEquals("999999990", content.getCustomerTin());
        assertEquals("FT 2026/123", content.getInvoiceId());
        assertEquals("ABCD1234-123", content.getAtcud());
        assertEquals("2026-10-01", content.getInvoiceDate());
        assertEquals(new BigDecimal("12.30"), content.getTotal());
        assertEquals(new BigDecimal("10.00"), content.getFirstTaxable().basicsStandardRate());
        assertEquals(RAW, content.getRaw());
        assertEquals(DATE, invoice.getDate());
        assertEquals("Water Co", invoice.getFromName());
        assertEquals("Fatura FT 2026/123", invoice.getSubject());
        assertTrue(invoice.getId().startsWith("api-"));
    }

    @Test
    void usesRequestDateWhenRawHasNoInvoiceDate() {
        when(invoiceProcessor.storeInvoice(any(), anyString())).thenReturn(result(InvoiceResult.Status.APPENDED));

        resource.create(request("A:123456789*G:FT 1/1*O:5.00"));

        assertEquals("2026-10-02", captureInvoice().getInvoiceContent().getInvoiceDate());
    }

    @Test
    void descriptionIsOptional() {
        when(invoiceProcessor.storeInvoice(any(), anyString())).thenReturn(result(InvoiceResult.Status.APPENDED));

        resource.create(new InvoiceRequest(DATE, "Water Co", null, RAW));

        assertEquals("", captureInvoice().getSubject());
    }

    @Test
    void mapsResultsToStatusCodes() {
        when(invoiceProcessor.storeInvoice(any(), anyString()))
            .thenReturn(result(InvoiceResult.Status.ALREADY_EXISTS));
        assertEquals(200, resource.create(request(RAW)).getStatus());

        when(invoiceProcessor.storeInvoice(any(), anyString()))
            .thenReturn(result(InvoiceResult.Status.ALREADY_PROCESSED));
        assertEquals(200, resource.create(request(RAW)).getStatus());

        when(invoiceProcessor.storeInvoice(any(), anyString())).thenReturn(result(InvoiceResult.Status.ERROR));
        assertEquals(502, resource.create(request(RAW)).getStatus());
    }

    private Invoice captureInvoice() {
        final ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceProcessor).storeInvoice(captor.capture(), eq("Water Co"));
        return captor.getValue();
    }

    private static InvoiceRequest request(final String raw) {
        return new InvoiceRequest(DATE, "Water Co", "Fatura FT 2026/123", raw);
    }

    private static InvoiceResult result(final InvoiceResult.Status status) {
        return new InvoiceResult("FT 2026/123", "123456789", new BigDecimal("12.30"), "other", status, "");
    }
}

package com.amfalmeida.mailhawk.api;

import com.amfalmeida.mailhawk.email.model.InvoiceResult;
import com.amfalmeida.mailhawk.service.ApiInvoiceService;
import com.amfalmeida.mailhawk.service.InvoiceRequestException;
import com.amfalmeida.mailhawk.service.InvoiceRequestException.Reason;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvoiceResourceTest {

    private static final String RAW = "A:123456789*B:999999990*C:PT*D:FT*E:N*F:20261001*G:FT 2026/123"
        + "*H:ABCD1234-123*I1:PT*I7:10.00*I8:2.30*N:2.30*O:12.30*Q:abcd*R:1234";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 2);
    private static final String FILE = "base64-file";

    private ApiInvoiceService apiInvoiceService;
    private InvoiceResource resource;

    @BeforeEach
    void setUp() {
        apiInvoiceService = mock(ApiInvoiceService.class);
        resource = new InvoiceResource(apiInvoiceService);
    }

    @Test
    void createForwardsFieldsToService() {
        when(apiInvoiceService.processQrCode(any(), any(), any(), any())).thenReturn(result(InvoiceResult.Status.APPENDED));

        final Response response = resource.create(new InvoiceRequest(DATE, "Water Co", "Fatura FT 2026/123", RAW));

        assertEquals(201, response.getStatus());
        assertEquals(result(InvoiceResult.Status.APPENDED), response.getEntity());
        verify(apiInvoiceService).processQrCode(RAW, DATE, "Water Co", "Fatura FT 2026/123");
    }

    @Test
    void createMapsResultStatusToHttpStatus() {
        when(apiInvoiceService.processQrCode(any(), any(), any(), any()))
            .thenReturn(result(InvoiceResult.Status.ALREADY_EXISTS));
        assertEquals(200, resource.create(request()).getStatus());

        when(apiInvoiceService.processQrCode(any(), any(), any(), any()))
            .thenReturn(result(InvoiceResult.Status.ALREADY_PROCESSED));
        assertEquals(200, resource.create(request()).getStatus());

        when(apiInvoiceService.processQrCode(any(), any(), any(), any())).thenReturn(result(InvoiceResult.Status.ERROR));
        assertEquals(502, resource.create(request()).getStatus());
    }

    @Test
    void createReturnsServiceFailureAsJsonError() {
        when(apiInvoiceService.processQrCode(any(), any(), any(), any()))
            .thenThrow(new InvoiceRequestException(Reason.INVALID_INPUT, "Field 'raw' is required"));

        final Response response = resource.create(null);

        assertEquals(400, response.getStatus());
        assertEquals(Map.of("error", "Field 'raw' is required"), response.getEntity());
    }

    @Test
    void createFileMapsResultsToStatusCodes() {
        when(apiInvoiceService.processFile(any(), any(), any(), any()))
            .thenReturn(List.of(result(InvoiceResult.Status.APPENDED), result(InvoiceResult.Status.ALREADY_EXISTS)));
        assertEquals(201, resource.createFromFile(fileRequest()).getStatus());

        when(apiInvoiceService.processFile(any(), any(), any(), any()))
            .thenReturn(List.of(result(InvoiceResult.Status.ALREADY_EXISTS)));
        assertEquals(200, resource.createFromFile(fileRequest()).getStatus());

        when(apiInvoiceService.processFile(any(), any(), any(), any()))
            .thenReturn(List.of(result(InvoiceResult.Status.APPENDED), result(InvoiceResult.Status.ERROR)));
        assertEquals(502, resource.createFromFile(fileRequest()).getStatus());
    }

    @Test
    void createFileForwardsFieldsToService() {
        when(apiInvoiceService.processFile(any(), any(), any(), any())).thenReturn(List.of());

        resource.createFromFile(new FileInvoiceRequest(DATE, "NOS", "WOO", FILE));

        verify(apiInvoiceService).processFile(FILE, DATE, "NOS", "WOO");
    }

    @Test
    void createFileMapsFailuresToStatusCodes() {
        assertFileFailure(Reason.INVALID_INPUT, 400);
        assertFileFailure(Reason.NO_QR_CODE, 422);
        assertFileFailure(Reason.STORAGE_FAILED, 500);
    }

    private void assertFileFailure(final Reason reason, final int status) {
        doThrow(new InvoiceRequestException(reason, "message"))
            .when(apiInvoiceService).processFile(any(), any(), any(), any());

        final Response response = resource.createFromFile(fileRequest());

        assertEquals(status, response.getStatus());
        assertEquals(Map.of("error", "message"), response.getEntity());
    }

    private static InvoiceRequest request() {
        return new InvoiceRequest(DATE, "Water Co", "Fatura FT 2026/123", RAW);
    }

    private static FileInvoiceRequest fileRequest() {
        return new FileInvoiceRequest(DATE, "NOS", "WOO", FILE);
    }

    private static InvoiceResult result(final InvoiceResult.Status status) {
        return new InvoiceResult("FT 2026/123", "123456789", new BigDecimal("12.30"), "other", status, "");
    }
}

package com.amfalmeida.mailhawk.api;

import com.amfalmeida.mailhawk.email.model.InvoiceResult;
import com.amfalmeida.mailhawk.service.ApiInvoiceService;
import com.amfalmeida.mailhawk.service.InvoiceRequestException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * REST entry point for invoices that don't arrive by email. The request is validated and stored by
 * {@link ApiInvoiceService}; this class only maps the request body and the outcome to HTTP.
 * The API key is checked by {@link ApiKeyFilter}.
 */
@ApiKeyRequired
@Path("/api/invoices")
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class InvoiceResource {

    private final ApiInvoiceService apiInvoiceService;

    /**
     * Stores one invoice from its raw AT QR code string.
     *
     * @return 201 when appended, 200 when it already existed, 502 when the Sheets write failed,
     *         400 for a missing date or an unparseable raw value
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(final InvoiceRequest request) {
        final InvoiceRequest body = request != null ? request : new InvoiceRequest(null, null, null, null);
        try {
            final InvoiceResult result = apiInvoiceService.processQrCode(
                body.raw(), body.date(), body.entityName(), body.description());
            return Response.status(statusOf(List.of(result))).entity(result).build();
        } catch (final InvoiceRequestException e) {
            return failure(e);
        }
    }

    /**
     * Stores every invoice found in a PDF or image sent base64-encoded in {@code file}.
     *
     * @return one {@link InvoiceResult} per invoice found: 201 when at least one was appended, 200 when all already
     *         existed, 502 when any Sheets write failed (see the per-invoice status), 400 for a missing date, a
     *         missing or invalid base64 file, or an unsupported file type, 422 when no valid AT QR code was found,
     *         500 when the file couldn't be stored temporarily
     */
    @POST
    @Path("file")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createFromFile(final FileInvoiceRequest request) {
        final FileInvoiceRequest body = request != null ? request : new FileInvoiceRequest(null, null, null, null);
        try {
            final List<InvoiceResult> results = apiInvoiceService.processFile(
                body.file(), body.date(), body.entityName(), body.description());
            return Response.status(statusOf(results)).entity(results).build();
        } catch (final InvoiceRequestException e) {
            return failure(e);
        }
    }

    /**
     * 502 if any result is an error, 201 if any invoice was appended, otherwise 200.
     */
    private static Response.Status statusOf(final List<InvoiceResult> results) {
        if (results.stream().anyMatch(result -> result.status() == InvoiceResult.Status.ERROR)) {
            return Response.Status.BAD_GATEWAY;
        }
        if (results.stream().anyMatch(result -> result.status() == InvoiceResult.Status.APPENDED)) {
            return Response.Status.CREATED;
        }
        return Response.Status.OK;
    }

    private static Response failure(final InvoiceRequestException e) {
        return Response.status(httpStatus(e.getReason()))
            .entity(Map.of("error", e.getMessage()))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    private static int httpStatus(final InvoiceRequestException.Reason reason) {
        return switch (reason) {
            case INVALID_INPUT -> Response.Status.BAD_REQUEST.getStatusCode();
            case NO_QR_CODE -> 422;
            case STORAGE_FAILED -> Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
        };
    }
}

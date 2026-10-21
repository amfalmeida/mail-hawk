package com.amfalmeida.mailhawk.api;

import com.amfalmeida.common.MdcSetter;
import com.amfalmeida.mailhawk.email.model.Invoice;
import com.amfalmeida.mailhawk.email.model.InvoiceContent;
import com.amfalmeida.mailhawk.email.model.InvoiceResult;
import com.amfalmeida.mailhawk.service.InvoiceProcessor;
import com.amfalmeida.mailhawk.service.QrCodeParser;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;

/**
 * REST entry point for invoices that don't arrive by email. It parses the raw AT QR code string the same way the
 * email pipeline does and stores the result through {@link InvoiceProcessor#storeInvoice(Invoice, String)}, so type
 * resolution, Sheets, Actual Budget and dedupe behave the same.
 * The API key is checked by {@link ApiKeyFilter}.
 */
@Slf4j
@ApiKeyRequired
@Path("/api/invoices")
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class InvoiceResource {

    private final InvoiceProcessor invoiceProcessor;
    private final QrCodeParser qrCodeParser;

    /**
     * Stores one invoice.
     *
     * @return 201 when appended, 200 when it already existed, 502 when the Sheets write failed,
     *         400 for a missing date or an unparseable raw value
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(final InvoiceRequest request) {
        final String id = "api-" + UUID.randomUUID();

        try (MdcSetter ignored = new MdcSetter(id)) {
            if (request == null || request.raw() == null || request.raw().isBlank()) {
                return error(Response.Status.BAD_REQUEST, "Field 'raw' is required");
            }
            if (request.date() == null) {
                return error(Response.Status.BAD_REQUEST, "Field 'date' is required (yyyy-MM-dd)");
            }

            final InvoiceContent invoiceContent = qrCodeParser.parseQrCodeString(request.raw().trim());
            if (invoiceContent == null
                    || isBlank(invoiceContent.getInvoiceId())
                    || isBlank(invoiceContent.getIssuerTin())) {
                return error(Response.Status.BAD_REQUEST,
                    "Field 'raw' is not a valid AT QR code: issuer NIF (A) and invoice ID (G) are required");
            }
            if (isBlank(invoiceContent.getInvoiceDate())) {
                invoiceContent.setInvoiceDate(request.date().toString());
            }

            final Invoice invoice = Invoice.builder()
                .id(id)
                .subject("")
                .fromAddress("")
                .fromName(request.entityName() != null ? request.entityName().trim() : "")
                .toAddress("")
                .date(request.date())
                .filename("")
                .invoiceContent(invoiceContent)
                .build();

            log.info("API invoice: {} | Issuer: {} | Entity: {} | Total: {}",
                invoiceContent.getInvoiceId(), invoiceContent.getIssuerTin(), invoice.getFromName(),
                invoiceContent.getTotal());
            return toResponse(invoiceProcessor.storeInvoice(invoice, request.entityName()));
        }
    }

    private static Response toResponse(final InvoiceResult result) {
        final Response.Status status = switch (result.status()) {
            case APPENDED -> Response.Status.CREATED;
            case ALREADY_EXISTS, ALREADY_PROCESSED -> Response.Status.OK;
            case ERROR -> Response.Status.BAD_GATEWAY;
        };
        return Response.status(status).entity(result).build();
    }

    private static Response error(final Response.Status status, final String message) {
        return Response.status(status).entity(Map.of("error", message)).type(MediaType.APPLICATION_JSON).build();
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}

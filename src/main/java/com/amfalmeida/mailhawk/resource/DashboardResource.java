package com.amfalmeida.mailhawk.resource;

import com.amfalmeida.mailhawk.db.entity.ProcessedInvoice;
import com.amfalmeida.mailhawk.service.DatabaseService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/dashboard")
public class DashboardResource {

    @Inject
    Template dashboard;

    @Inject
    DatabaseService databaseService;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index(@QueryParam("page") @DefaultValue("0") final int page) {
        final int size = 20;
        final long total = databaseService.countInvoices();
        final long totalPages = (total + size - 1) / size;
        TemplateInstance instance = dashboard.instance()
            .data("invoices", databaseService.listInvoices(page, size))
            .data("page", page)
            .data("total", total)
            .data("pageSize", size)
            .data("totalPages", totalPages);
        return instance.render().toString();
    }

    @GET
    @Path("api/invoices")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ProcessedInvoice> listInvoices(
            @QueryParam("page") @DefaultValue("0") final int page,
            @QueryParam("size") @DefaultValue("20") final int size) {
        return databaseService.listInvoices(page, size);
    }

    @GET
    @Path("api/invoices/{atcud}")
    @Produces(MediaType.APPLICATION_JSON)
    public ProcessedInvoice getInvoice(@PathParam("atcud") final String atcud) {
        return databaseService.findInvoiceByAtcud(atcud);
    }

    @GET
    @Path("search")
    @Produces(MediaType.TEXT_HTML)
    public String search(@QueryParam("q") final String query,
                         @QueryParam("page") @DefaultValue("0") final int page) {
        final int size = 20;
        final List<ProcessedInvoice> results;
        final long total;
        final long totalPages;
        if (query != null && !query.isBlank()) {
            results = databaseService.searchInvoices(query);
            total = results.size();
            totalPages = 1;
        } else {
            results = databaseService.listInvoices(page, size);
            total = databaseService.countInvoices();
            totalPages = (total + size - 1) / size;
        }
        TemplateInstance instance = dashboard.instance()
            .data("invoices", results)
            .data("query", query)
            .data("page", page)
            .data("total", total)
            .data("pageSize", size)
            .data("totalPages", totalPages);
        return instance.render().toString();
    }
}

package com.amfalmeida.mailhawk.client;

import com.amfalmeida.mailhawk.dto.TransactionImportRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

@RegisterRestClient(configKey = "actual-budget", baseUri = "http://localhost:5007")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RegisterProvider(JsonLoggingFilter.class)
public interface ActualBudgetClient {

    @POST
    @Path("/v1/budgets/{budgetSyncId}/accounts/{accountId}/transactions/import")
    Map<String, Object> importTransactions(
            @PathParam("budgetSyncId") String budgetSyncId,
            @PathParam("accountId") String accountId,
            @HeaderParam("x-api-key") String apiKey,
            TransactionImportRequest request);
}

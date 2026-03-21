package com.amfalmeida.mailhawk.client;

import com.amfalmeida.mailhawk.dto.TransactionDto;
import com.amfalmeida.mailhawk.dto.TransactionImportRequest;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@DisplayName("Actual Budget Client Tests")
class ActualBudgetClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Inject
    @RestClient
    ActualBudgetClient client;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("Should send proper JSON when importing transactions")
    void shouldSendProperJsonWhenImportingTransactions() {
        String expectedRequestBody = """
            {
              "transactions" : [ {
                "account" : "test-account-id",
                "date" : "2024-01-15",
                "amount" : -1500,
                "payee_name" : "EDP",
                "category" : "cat-123",
                "notes" : "invoice.pdf",
                "imported_id" : "ABC123",
                "cleared" : true
              } ]
            }
            """;

        wireMock.stubFor(post(urlPathMatching("/budgets/.+/accounts/.+/transactions/import"))
                .withHeader("x-api-key", equalTo("test-api-key"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(expectedRequestBody, true, false))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "data": {
                                "imported": ["id-1"]
                              }
                            }
                            """)));

        TransactionDto dto = new TransactionDto(
                "test-account-id",
                "2024-01-15",
                -1500,
                "EDP",
                "cat-123",
                "invoice.pdf",
                "ABC123",
                true
        );

        TransactionImportRequest request = new TransactionImportRequest(List.of(dto));

        var result = client.importTransactions(
                "budget-123",
                "account-456",
                "test-api-key",
                request
        );

        assertNotNull(result);
        wireMock.verify(postRequestedFor(urlPathMatching("/budgets/.+/accounts/.+/transactions/import"))
                .withHeader("Content-Type", containing("application/json")));
    }

    @Test
    @DisplayName("Should log request body for debugging")
    void shouldLogRequestBodyForDebugging() {
        TransactionDto dto = new TransactionDto(
                "acc-1",
                "2024-03-21",
                -9999,
                "TestPayee",
                "cat-1",
                "test.pdf",
                "ATCUD-123",
                true
        );

        TransactionImportRequest request = new TransactionImportRequest(List.of(dto));

        wireMock.stubFor(post(urlPathMatching("/budgets/.+/accounts/.+/transactions/import"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\": {}}")));

        client.importTransactions("budget-1", "account-1", "key-1", request);

        List<com.github.tomakehurst.wiremock.verification.LoggedRequest> requests =
                wireMock.findAll(postRequestedFor(urlPathMatching("/budgets/.+/accounts/.+/transactions/import")));

        assertEquals(1, requests.size());
        String body = requests.get(0).getBodyAsString();
        System.out.println("=== ACTUAL REQUEST BODY ===");
        System.out.println(body);
        System.out.println("===========================");

        assertTrue(body.contains("\"transactions\"") || body.contains("transactions"),
                "Body should contain transactions field. Actual: " + body);
        assertFalse(body.contains("TransactionImportRequest@"),
                "Body should NOT contain toString output. Actual: " + body);
    }
}
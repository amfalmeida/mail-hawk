package com.amfalmeida.mailhawk.client;

import com.amfalmeida.mailhawk.dto.TransactionDto;
import com.amfalmeida.mailhawk.dto.TransactionImportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Actual Budget Client Tests")
class ActualBudgetClientTest {

    @Test
    @DisplayName("Should serialize TransactionImportRequest correctly")
    void shouldSerializeTransactionImportRequestCorrectly() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        
        TransactionDto dto = new TransactionDto(
                "acc-1",
                "2024-03-21",
                -9999,
                "TestPayee",
                "cat-1",
                "test.pdf",
                "ATCUD-999",
                true
        );

        TransactionImportRequest request = new TransactionImportRequest(List.of(dto));
        
        assertNotNull(request.transactions());
        assertEquals(1, request.transactions().size());
        assertEquals("acc-1", request.transactions().get(0).account());
        assertEquals("2024-03-21", request.transactions().get(0).date());
        assertEquals(-9999, request.transactions().get(0).amount());
        assertEquals("TestPayee", request.transactions().get(0).payeeName());

        String json = mapper.writeValueAsString(request);
        assertTrue(json.contains("\"transactions\""));
        assertTrue(json.contains("\"payee_name\""));
        assertTrue(json.contains("TestPayee"));
    }
}
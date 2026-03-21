package com.amfalmeida.mailhawk.client;

import com.amfalmeida.mailhawk.dto.TransactionDto;
import com.amfalmeida.mailhawk.dto.TransactionImportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransactionDtoTest {

    @Test
    void shouldSerializeTransactionDtoToJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        
        TransactionDto dto = new TransactionDto(
            "account-123",
            "2024-03-21",
            -1500,
            "EDP",
            "cat-456",
            "invoice.pdf",
            "ATCUD-123",
            true
        );

        String json = mapper.writeValueAsString(dto);
        
        System.out.println("=== Serialized JSON ===");
        System.out.println(json);
        System.out.println("======================");
        
        assertTrue(json.contains("\"payee_name\""), "Should serialize payee_name with snake_case");
        assertTrue(json.contains("\"imported_id\""), "Should serialize imported_id with snake_case");
        assertTrue(json.contains("EDP"), "Should contain payee name");
        assertFalse(json.contains("TransactionDto@"), "Should NOT contain toString output");
    }

    @Test
    void shouldSerializeTransactionImportRequestToJson() throws Exception {
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

        String json = mapper.writeValueAsString(request);
        
        System.out.println("=== Request JSON ===");
        System.out.println(json);
        System.out.println("===================");
        
        assertTrue(json.contains("\"transactions\""), "Should contain transactions array");
        assertTrue(json.contains("\"payee_name\""), "Should serialize payee_name");
        assertFalse(json.contains("TransactionImportRequest@"), "Should NOT contain toString");
        assertFalse(json.contains("TransactionDto@"), "Should NOT contain toString");
    }
    
    @Test
    void shouldDeserializeTransactionDtoFromJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        
        String json = "{\"account\":\"acc-1\",\"date\":\"2024-03-21\",\"amount\":-9999,\"payee_name\":\"TestPayee\",\"category\":\"cat-1\",\"notes\":\"test.pdf\",\"imported_id\":\"ATCUD-999\",\"cleared\":true}";
        
        TransactionDto dto = mapper.readValue(json, TransactionDto.class);
        
        assertEquals("acc-1", dto.account());
        assertEquals("2024-03-21", dto.date());
        assertEquals(-9999, dto.amount());
        assertEquals("TestPayee", dto.payeeName());
        assertEquals("cat-1", dto.category());
        assertEquals("test.pdf", dto.notes());
        assertEquals("ATCUD-999", dto.importedId());
        assertTrue(dto.cleared());
    }
}
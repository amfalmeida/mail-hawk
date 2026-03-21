package com.amfalmeida.mailhawk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransactionDto(
    String account,
    String date,
    Integer amount,
    @JsonProperty("payee_name") String payeeName,
    String category,
    String notes,
    @JsonProperty("imported_id") String importedId,
    Boolean cleared
) {}
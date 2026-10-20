package com.amfalmeida.mailhawk.client.dto;

import java.util.List;

public record TransactionImportRequest(List<TransactionDto> transactions) {}

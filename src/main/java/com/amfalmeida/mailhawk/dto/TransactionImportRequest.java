package com.amfalmeida.mailhawk.dto;

import java.util.List;

public record TransactionImportRequest(List<TransactionDto> transactions) {}
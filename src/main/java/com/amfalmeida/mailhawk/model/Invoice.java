package com.amfalmeida.mailhawk.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class Invoice {
    private String id;
    private String subject;
    private String fromAddress;
    private String fromName;
    private String toAddress;
    private LocalDate date;
    private String filename;
    private String filePath;
    private QrCodeContent qrCode;
    private InvoiceType invoiceType;
}
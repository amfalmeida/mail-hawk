package com.amfalmeida.mailhawk.model;

import java.math.BigDecimal;

public record RecurrentBill(
    String type,
    String local,
    String entityEmail,
    String entityName,
    String nif,
    String customerNif,
    BigDecimal value,
    BigDecimal tax,
    Integer paymentDay,
    String until,
    String comments
) {}

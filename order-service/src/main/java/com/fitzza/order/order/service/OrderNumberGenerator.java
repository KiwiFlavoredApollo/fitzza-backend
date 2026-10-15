package com.fitzza.order.order.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OrderNumberGenerator {

    private static final String PREFIX = "ORD";
    private static final int RANDOM_LENGTH = 10;

    public String generateOrderNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_LENGTH).toUpperCase();
        return PREFIX + "-" + date + "-" + random;
    }
}

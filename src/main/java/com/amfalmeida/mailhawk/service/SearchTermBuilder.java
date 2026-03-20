package com.amfalmeida.mailhawk.service;

import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

public final class SearchTermBuilder {

    private SearchTermBuilder() {}

    public static SearchTerm buildDateFilter(LocalDate searchDate) {
        return new ReceivedDateTerm(
            ComparisonTerm.GE,
            Date.from(searchDate.atStartOfDay(ZoneId.systemDefault()).toInstant())
        );
    }

    public static boolean matchesSubject(String subject, List<String> subjectTerms) {
        if (subject == null || subjectTerms == null || subjectTerms.isEmpty()) {
            return true;
        }

        String lowerSubject = subject.toLowerCase();
        return subjectTerms.stream()
            .filter(term -> term != null && !term.isBlank())
            .anyMatch(term -> lowerSubject.contains(term.toLowerCase()));
    }
}
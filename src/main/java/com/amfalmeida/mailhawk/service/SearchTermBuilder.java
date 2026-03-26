package com.amfalmeida.mailhawk.service;

import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SizeTerm;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

public final class SearchTermBuilder {

    private SearchTermBuilder() {}

    public static SearchTerm buildDateFilter(final LocalDate searchDate) {
        return new ReceivedDateTerm(
            ComparisonTerm.GE,
            Date.from(searchDate.atStartOfDay(ZoneId.systemDefault()).toInstant())
        );
    }

    public static SearchTerm buildDateAndSizeFilter(
            final LocalDate searchDate,
            final long minSize) {
        SearchTerm dateTerm = buildDateFilter(searchDate);
        if (minSize <= 0) {
            return dateTerm;
        }
        SearchTerm sizeTerm = new SizeTerm(ComparisonTerm.GE, (int) minSize);
        return new AndTerm(dateTerm, sizeTerm);
    }

    public static boolean matchesSubject(
            final String subject,
            final List<String> subjectTerms) {
        if (subject == null || subjectTerms == null || subjectTerms.isEmpty()) {
            return true;
        }

        String lowerSubject = subject.toLowerCase();
        return subjectTerms.stream()
            .filter(term -> term != null && !term.isBlank())
            .anyMatch(term -> lowerSubject.contains(term.toLowerCase()));
    }
}

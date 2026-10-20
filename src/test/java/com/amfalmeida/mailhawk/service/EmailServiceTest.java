package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.AppConfig;
import com.amfalmeida.mailhawk.email.EmailProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Email Service Tests")
class EmailServiceTest {

    private EmailProcessor emailProcessor;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailProcessor = mock(EmailProcessor.class);
        final AppConfig appConfig = mock(AppConfig.class);
        when(appConfig.lastCheckedCacheHours()).thenReturn(24);
        emailService = new EmailService(emailProcessor, mock(InvoiceProcessor.class), appConfig);
        emailService.initCache();
    }

    @Test
    @DisplayName("Should search from days-older on first run and from last check time afterwards")
    void shouldUseLastCheckedTimeAfterFirstRun() {
        when(emailProcessor.checkAndProcessEmails(any(), any(), any())).thenReturn(Optional.of(List.of()));
        final LocalDateTime beforeFirstRun = LocalDateTime.now();

        emailService.checkEmails();
        emailService.checkEmails();

        final ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(emailProcessor, times(2)).checkAndProcessEmails(any(), any(), captor.capture());

        final List<LocalDateTime> searchStarts = captor.getAllValues();
        assertNull(searchStarts.get(0), "First run should fall back to days-older");
        assertNotNull(searchStarts.get(1), "Second run should use the cached last check time");
        assertFalse(searchStarts.get(1).isBefore(beforeFirstRun));
    }

    @Test
    @DisplayName("Should not advance last check time when the check fails")
    void shouldNotAdvanceLastCheckedTimeWhenCheckFails() {
        when(emailProcessor.checkAndProcessEmails(any(), any(), any())).thenReturn(Optional.empty());

        emailService.checkEmails();
        emailService.checkEmails();

        final ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(emailProcessor, times(2)).checkAndProcessEmails(any(), any(), captor.capture());

        assertNull(captor.getAllValues().get(1), "Failed check should leave the search window unchanged");
    }
}

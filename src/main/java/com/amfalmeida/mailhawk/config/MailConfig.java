package com.amfalmeida.mailhawk.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;

@ConfigMapping(prefix = "mail")
public interface MailConfig {
    @WithDefault("imap.gmail.com")
    String host();

    @WithDefault("993")
    int port();

    @WithDefault("")
    String username();

    @WithDefault("")
    String password();

    @WithDefault("INBOX")
    String folder();

    @WithDefault("30")
    int daysOlder();

    @WithDefault("fatura,factura,extracto,recibo")
    List<String> subjectTerms();

    @WithDefault("true")
    boolean onlyAttachments();

    @WithDefault("0")
    int maxEmails();

    @WithDefault("")
    String pdfPasswords();

    @WithDefault("false")
    boolean debug();
}
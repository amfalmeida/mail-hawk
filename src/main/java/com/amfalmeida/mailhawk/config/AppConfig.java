package com.amfalmeida.mailhawk.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "app")
public interface AppConfig {
    @WithDefault("share/mail_hawk/mail_hawk.db")
    String dbPath();

    @WithDefault("60")
    int checkInterval();

    @WithDefault("300")
    int configSyncInterval();

    @WithDefault("other")
    String defaultInvoiceType();

    @WithDefault("360")
    int recurrentCheckInterval();
}
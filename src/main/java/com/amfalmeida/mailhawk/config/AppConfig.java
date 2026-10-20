package com.amfalmeida.mailhawk.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "app")
public interface AppConfig {
    @WithDefault("60")
    int checkInterval();

    @WithDefault("300")
    int configSyncInterval();

    @WithDefault("other")
    String defaultInvoiceType();

    @WithDefault("360")
    int recurrentCheckInterval();

    @WithDefault("24")
    int processedInvoiceCacheHours();

    @WithDefault("10000")
    long processedInvoiceCacheSize();

    @WithDefault("24")
    int lastCheckedCacheHours();

    @WithDefault("1")
    int configCacheHours();
}

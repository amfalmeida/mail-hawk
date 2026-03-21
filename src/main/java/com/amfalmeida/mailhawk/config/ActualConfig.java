package com.amfalmeida.mailhawk.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "actual")
public interface ActualConfig {
    @WithDefault("false")
    boolean enabled();

    @WithDefault("")
    String url();

    @WithDefault("")
    String apiKey();

    @WithDefault("")
    String budgetSyncId();

    @WithDefault("")
    String accountId();
}
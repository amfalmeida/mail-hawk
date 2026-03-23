package com.amfalmeida.mailhawk.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "actual")
public interface ActualConfig {
    @WithDefault("false")
    Optional<Boolean> enabled();

    @WithDefault("")
    Optional<String> url();

    @WithDefault("")
    Optional<String> apiKey();

    @WithDefault("")
    Optional<String> budgetSyncId();

    @WithDefault("")
    Optional<String> accountId();
}
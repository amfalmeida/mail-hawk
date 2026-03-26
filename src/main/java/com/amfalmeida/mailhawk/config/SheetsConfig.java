package com.amfalmeida.mailhawk.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "sheets")
public interface SheetsConfig {
    @WithDefault("")
    String id();

    @WithDefault("Bills values")
    String sheetName();

    @WithDefault("config")
    String configSheet();

    @WithDefault("recurrent")
    String recurrentSheet();

    @WithDefault("")
    String encodedCredentials();
}

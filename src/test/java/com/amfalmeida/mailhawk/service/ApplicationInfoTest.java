package com.amfalmeida.mailhawk.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;

import org.junit.jupiter.api.Test;

class ApplicationInfoTest {

    private static ApplicationInfo newInfo(final String version) {
        final ApplicationInfo info = new ApplicationInfo();
        info.appVersion = version;
        return info;
    }

    @Test
    void describeVersionUsesClosestTagWhenAvailable() {
        final ApplicationInfo info = newInfo("2.0.0.4");
        final Properties git = new Properties();
        git.setProperty("git.closest.tag.name", "v2.2.10");
        git.setProperty("git.commit.id.describe", "v2.2.10-5-gb825ccd");
        git.setProperty("git.commit.id.abbrev", "b825ccd");
        git.setProperty("git.branch", "main");

        assertEquals("v2.2.10 (tags: none, commit: b825ccd, branch: main)", info.describeVersion(git));
    }

    @Test
    void describeVersionFallsBackToDescribeWhenNoTag() {
        final ApplicationInfo info = newInfo("2.0.0.4");
        final Properties git = new Properties();
        git.setProperty("git.commit.id.describe", "1.0.0-3-g1234567");

        assertEquals("1.0.0-3-g1234567 (tags: none, commit: unknown, branch: unknown)", info.describeVersion(git));
    }

    @Test
    void describeVersionFallsBackToPomVersionWhenNoGitInfo() {
        final ApplicationInfo info = newInfo("2.0.0.4");

        assertEquals("2.0.0.4 (tags: none, commit: unknown, branch: unknown)", info.describeVersion(new Properties()));
    }
}
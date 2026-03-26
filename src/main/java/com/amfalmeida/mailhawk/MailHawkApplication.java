package com.amfalmeida.mailhawk;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public final class MailHawkApplication implements QuarkusApplication {

    @Override
    public int run(final String... args) throws Exception {
        Quarkus.waitForExit();
        return 0;
    }
}

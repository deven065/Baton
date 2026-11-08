package com.bramhastra.baton;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Lets tests in sub-packages import the package-private Testcontainers configuration. */
public final class TestcontainersConfigurationAccess {

    private TestcontainersConfigurationAccess() {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestcontainersConfiguration.class)
    public static class Config {
    }
}

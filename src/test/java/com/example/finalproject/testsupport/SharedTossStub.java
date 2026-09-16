package com.example.finalproject.testsupport;

import org.junit.jupiter.api.extension.ExtensionContext;

/** JVM에서 하나의 WireMock 서버를 공유한다. */
public class SharedTossStub extends TossStub {

    private boolean started;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (started) {
            return;
        }
        started = true;
        super.beforeAll(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        server.resetAll();
    }

    @Override
    public void afterEach(ExtensionContext context) {
    }
}

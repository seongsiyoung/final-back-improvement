package com.example.finalproject.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Tag("manual")
class LoadTestDataSeederAgainstHarnessDb {

    @Autowired
    private LoadTestDataSeeder seeder;

    private static final int LOAD_TEST_USER_COUNT = 500;

    @Test
    void seed() {
        for (int i = 0; i < LOAD_TEST_USER_COUNT; i++) {
            seeder.seedUserWithAddress("k6-load-user-" + i + "@test.com", "loadtest1234!");
        }
        seeder.seedStoreWithProducts(300, 100_000);
    }
}

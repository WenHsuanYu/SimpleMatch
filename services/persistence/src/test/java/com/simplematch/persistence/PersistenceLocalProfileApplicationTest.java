package com.simplematch.persistence;

import com.simplematch.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("local")
class PersistenceLocalProfileApplicationTest {
    @Autowired
    private PlatformProperties platformProperties;

    @Test
    void startsWithTheLocalProfile() {
        assertThat(platformProperties.environment()).isEqualTo("local");
    }
}

package com.simplematch.quickfixgateway;

import com.simplematch.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "simplematch.quickfix-gateway.acceptor-enabled=false",
        "simplematch.quickfix-gateway.data-plane-enabled=false",
        "simplematch.quickfix-gateway.replay-enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.main.web-application-type=none"
})
@ActiveProfiles("local")
class QuickFixGatewayLocalProfileApplicationTest {
    @Autowired
    private PlatformProperties platformProperties;

    @Test
    void startsWithTheLocalProfile() {
        assertThat(platformProperties.environment()).isEqualTo("local");
    }
}

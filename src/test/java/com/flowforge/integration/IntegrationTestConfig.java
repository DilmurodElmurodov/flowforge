package com.flowforge.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class IntegrationTestConfig {

    @Bean
    @Primary
    public RecordingNotificationSender recordingNotificationSender() {
        return new RecordingNotificationSender();
    }
}

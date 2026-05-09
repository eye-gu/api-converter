package com.apiconverter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "upstream")
public class UpstreamConfig {

    private String baseUrl;
    private String apiKey;
    private Map<String, String> modelMapping;
}

package io.github.jagatlabs.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.jagatlabs.mcp.config.JagatlabsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(JagatlabsProperties.class)
public class JagatlabsMcpApplication {
    public static void main(String[] args) { SpringApplication.run(JagatlabsMcpApplication.class, args); }
    @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    @Bean ObjectMapper upstreamObjectMapper() { return JsonMapper.builder().build(); }
}

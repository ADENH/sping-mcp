package io.github.jagatlabs.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("jagatlabs")
public record JagatlabsProperties(String mode, URI baseUrl, String token, Duration connectTimeout,
                                 Duration requestTimeout, int maxResponseBytes) {
    public JagatlabsProperties {
        if (!Set.of("mock", "live").contains(mode == null ? "" : mode) || baseUrl == null
                || baseUrl.getHost() == null || baseUrl.getRawUserInfo() != null
                || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || !(baseUrl.getPath().isEmpty() || baseUrl.getPath().equals("/"))) {
            throw new IllegalArgumentException("Invalid Jagatlabs mode or base URL; supply an origin without credentials, path or query");
        }
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(baseUrl.getHost());
        if ((mode.equals("live") && !"https".equals(baseUrl.getScheme()))
                || (mode.equals("mock") && !(loopback && "http".equals(baseUrl.getScheme())))) {
            throw new IllegalArgumentException("Live mode requires HTTPS; mock mode requires a loopback HTTP origin");
        }
        if (connectTimeout == null || requestTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout.isNegative() || requestTimeout.isZero() || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || requestTimeout.compareTo(Duration.ofSeconds(60)) > 0 || maxResponseBytes < 1024 || maxResponseBytes > 1048576) {
            throw new IllegalArgumentException("Timeouts must be positive (connect <=30s, request <=60s); response limit 1024..1048576 bytes");
        }
        token = token == null ? "" : token;
        if (token.chars().anyMatch(c -> c <= 32 || c == 127)) {
            throw new IllegalArgumentException("Jagatlabs token contains invalid characters");
        }
    }

    // Never include the secret in framework diagnostics.
    @Override public String toString() { return "JagatlabsProperties[mode=" + mode + ", token=REDACTED]"; }
}

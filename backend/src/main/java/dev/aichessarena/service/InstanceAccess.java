package dev.aichessarena.service;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Host/origin checks are independent of forwarded headers and apply to every credential endpoint. */
@Startup
@ApplicationScoped
public class InstanceAccess {
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private final OpenRouterKeyStore keys;
    private final String[] hosts;
    private final String publicOrigin;
    @ConfigProperty(name = "instance.frontend-port", defaultValue = "4200")
    int frontendPort = 4200;

    @Inject
    public InstanceAccess(OpenRouterKeyStore keys,
                          @ConfigProperty(name = "instance.allowed-hosts") Optional<String> allowedHosts,
                          @ConfigProperty(name = "instance.public-origin") Optional<String> publicOrigin) {
        this(keys, allowedHosts.orElse(""), publicOrigin.orElse(""));
    }

    public InstanceAccess(OpenRouterKeyStore keys, String allowedHosts, String publicOrigin) {
        this.keys = keys;
        this.hosts = Arrays.stream(allowedHosts.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(h -> !h.isBlank()).toArray(String[]::new);
        this.publicOrigin = publicOrigin;
    }

    @PostConstruct
    void initialize() {
        if (lan() && PrivateFiles.read(claimFile()) == null) {
            String token = setupToken();
            String host = Arrays.stream(hosts).filter(h -> !h.contains("*") && !LOOPBACK.contains(h)).findFirst().orElse("localhost");
            String setupOrigin = publicOrigin.isBlank() ? "http://" + host + ":" + frontendPort : publicOrigin;
            Logger.getLogger(InstanceAccess.class).infof("Claim this instance: %s/setup?token=%s", setupOrigin, token);
        }
    }

    boolean lan() {
        return Arrays.stream(hosts).anyMatch(h -> !LOOPBACK.contains(h));
    }

    private static String host(String value) {
        return value.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
    }

    public void requireHost(URI request) {
        if (request.getHost() == null || !allowed(host(request.getHost()))) {
            throw new WebApplicationException("Host is not in ALLOWED_HOSTS", 421);
        }
    }

    private boolean allowed(String candidate) {
        return LOOPBACK.contains(candidate) || Arrays.stream(hosts).anyMatch(h -> h.equals(candidate)
                || h.startsWith("*.") && (candidate.equals(h.substring(2)) || candidate.endsWith(h.substring(1)))
                || h.endsWith(".*") && candidate.startsWith(h.substring(0, h.length() - 1)));
    }

    public String origin(URI request, String origin, boolean required) {
        requireHost(request);
        if (origin == null && !required) return null;
        try {
            URI uri = URI.create(origin == null ? "" : origin);
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                    || uri.getRawAuthority() == null || uri.getUserInfo() != null
                    || !uri.getRawAuthority().equalsIgnoreCase(request.getRawAuthority())
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            requireHost(uri);
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("A same-origin browser request is required", 403);
        }
    }

    private Path claimFile() {
        return keys.directory().resolve("instance_claim");
    }

    synchronized String setupToken() {
        Path file = keys.directory().resolve("setup_token");
        String token = PrivateFiles.read(file);
        if (token == null) {
            token = Pkce.random();
            PrivateFiles.write(file, token);
        }
        return token;
    }

    public synchronized boolean authorized(String browser, URI request) {
        requireHost(request);
        if (browser == null || !browser.matches("[A-Za-z0-9_-]{43}")) return false;
        if (!lan() && LOOPBACK.contains(host(request.getHost()))) {
            if (PrivateFiles.read(claimFile()) == null) {
                PrivateFiles.write(claimFile(), Pkce.challenge(browser));
                PrivateFiles.delete(keys.directory().resolve("setup_token"));
            }
            return true;
        }
        return Pkce.challenge(browser).equals(PrivateFiles.read(claimFile()));
    }

    public void requireClaim(String browser, URI request) {
        if (!authorized(browser, request)) {
            throw new WebApplicationException("Claim this instance with the one-time setup link from the backend log", 403);
        }
    }

    public synchronized void claim(String browser, String token) {
        String expected = PrivateFiles.read(keys.directory().resolve("setup_token"));
        if (browser == null || !browser.matches("[A-Za-z0-9_-]{43}") || token == null || token.length() > 128 || expected == null
                || PrivateFiles.read(claimFile()) != null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new WebApplicationException("Setup token is invalid or already used", 403);
        }
        PrivateFiles.write(claimFile(), Pkce.challenge(browser));
        PrivateFiles.delete(keys.directory().resolve("setup_token"));
    }
}

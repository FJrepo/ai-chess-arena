package dev.aichessarena.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Loopback servers for connection setup: one that accepts TCP but never answers the TLS handshake, and one that
 * completes TLS with a throwaway self-signed certificate (generated with the JDK's keytool) and then never answers
 * the request. Close it to release held connections.
 */
final class LoopbackTls implements AutoCloseable {
    private static final char[] PASSWORD = "loopback".toCharArray();

    private final ServerSocket socket;
    private final CountDownLatch closed = new CountDownLatch(1);
    final AtomicInteger connections = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();
    private final SSLContext context;

    private LoopbackTls(ServerSocket socket, SSLContext context) {
        this.socket = socket;
        this.context = context;
    }

    /** Accepts TCP connections and never completes their TLS handshake. */
    static LoopbackTls stallingHandshake() throws IOException {
        LoopbackTls server = new LoopbackTls(new ServerSocket(0, 0, InetAddress.getLoopbackAddress()), null);
        Thread.startVirtualThread(() -> server.serve(false));
        return server;
    }

    /** Completes TLS, reads the whole request and never answers it. */
    static LoopbackTls silentAfterHandshake() throws Exception {
        SSLContext context = selfSigned();
        SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 0, InetAddress.getLoopbackAddress());
        LoopbackTls server = new LoopbackTls(socket, context);
        Thread.startVirtualThread(() -> server.serve(true));
        return server;
    }

    String baseUrl() {
        return "https://127.0.0.1:" + socket.getLocalPort();
    }

    /** A client that trusts this server's certificate (only for {@link #silentAfterHandshake()}). */
    HttpClient trustingClient() {
        return HttpClient.newBuilder().sslContext(context).version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    private void serve(boolean tls) {
        while (!socket.isClosed()) {
            try {
                Socket connection = socket.accept();
                connections.incrementAndGet();
                Thread.startVirtualThread(() -> hold(connection, tls));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void hold(Socket connection, boolean tls) {
        try (connection) {
            if (tls) {
                ((SSLSocket) connection).startHandshake();
                readRequest(connection.getInputStream());
                requests.incrementAndGet();
            }
            closed.await();
        } catch (IOException | InterruptedException ignored) {
            // released by close()
        }
    }

    private static void readRequest(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new IOException("request ended early");
            head.append((char) b);
        }
        int length = 0;
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
        }
        in.readNBytes(length);
    }

    private static SSLContext selfSigned() throws Exception {
        Path dir = Files.createTempDirectory("loopback-tls");
        Path store = dir.resolve("server.p12");
        Process keytool = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "loopback", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=localhost", "-ext", "san=ip:127.0.0.1,dns:localhost",
                "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD)))
                .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        if (keytool.waitFor() != 0) throw new IllegalStateException("keytool failed");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keys.load(in, PASSWORD);
        }
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("loopback", keys.getCertificate("loopback"));
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, PASSWORD);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return context;
    }

    @Override
    public void close() throws IOException {
        closed.countDown();
        socket.close();
    }
}

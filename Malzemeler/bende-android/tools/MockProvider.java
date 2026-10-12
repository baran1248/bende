import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Local HTTPS test double for the OpenAI-compatible provider contract. */
public final class MockProvider {
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger MAX_ACTIVE = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: MockProvider <pkcs12-path> <log-path>");
        char[] password = System.getenv("BENDE_MOCK_PASS").toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of(args[0]))) { keys.load(input, password); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);

        Path log = Path.of(args[1]);
        Files.deleteIfExists(log);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("0.0.0.0", 8443), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/models", exchange -> respond(exchange, 200, "{\"data\":[{\"id\":\"mock-agent\"}]}"));
        server.createContext("/v1/chat/completions", exchange -> {
            int active = ACTIVE.incrementAndGet();
            MAX_ACTIVE.accumulateAndGet(active, Math::max);
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String event = System.currentTimeMillis() + " START active=" + active + " path=" + exchange.getRequestURI() + " body=" + body + System.lineSeparator();
                Files.writeString(log, event, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                Thread.sleep(3500);
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Mock response: request completed.\"},\"finish_reason\":\"stop\"}]} ");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, "{\"error\":\"interrupted\"}");
            } finally {
                int remaining = ACTIVE.decrementAndGet();
                String event = System.currentTimeMillis() + " END active=" + remaining + " max=" + MAX_ACTIVE.get() + System.lineSeparator();
                Files.writeString(log, event, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.println("Mock HTTPS provider listening on :8443");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}

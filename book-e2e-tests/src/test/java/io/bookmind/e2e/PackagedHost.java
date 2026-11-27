package io.bookmind.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

final class PackagedHost implements AutoCloseable {
    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Process process;
    private int port;

    PackagedHost(Path home, boolean disabled) throws Exception {
        Path log = Files.createTempFile(home, "host-", ".log");
        String java = ProcessHandle.current().info().command().orElseThrow();
        process = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-jar", "host.jar",
                "--server.address=127.0.0.1", "--server.port=0", "--bookmind.plugins-dir=plugins",
                "--bookmind.library-dir=books", "--bookmind.data-dir=data", "--bookmind.rag.api-key=",
                "--spring.output.ansi.enabled=never",
                "--logging.level.org.springframework.boot.web.embedded.tomcat=INFO",
                "--bookmind.disabled-plugins=" + (disabled ? "pdf-pdfbox" : ""))
                .directory(home.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ready = false;
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (process.isAlive() && System.nanoTime() < deadline) {
                var match = PORT.matcher(Files.readString(log, StandardCharsets.ISO_8859_1));
                if (match.find()) {
                    port = Integer.parseInt(match.group(1));
                    assertThat(get("/health").path("status").asText()).isEqualTo("UP");
                    ready = true;
                    return;
                }
                Thread.sleep(150);
            }
            throw new IOException("Host startup failed. Output:\n" + Files.readString(log));
        } finally {
            if (!ready) {
                close();
            }
        }
    }

    JsonNode get(String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/api" + path);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    @Override
    public void close() throws InterruptedException {
        try {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("host stopped").isTrue();
            }
        } finally {
            client.close();
        }
    }
}

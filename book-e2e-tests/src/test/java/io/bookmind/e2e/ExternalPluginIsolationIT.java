package io.bookmind.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import io.bookmind.spi.BookFormatPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalPluginIsolationIT {
    private static final Path SAMPLES = Path.of("..", "samples");
    private static final String PDF = "sample-rag-handbook.pdf";
    private static final String PDF_SEARCH = "/rag/search?q=" + URLEncoder.encode("蓝鹭", StandardCharsets.UTF_8);

    @TempDir
    Path home;
    private Path plugins;
    private Path books;
    private Path hostJar;

    @BeforeEach
    void assembleInstallation() throws Exception {
        plugins = Files.createDirectory(home.resolve("plugins"));
        books = Files.createDirectory(home.resolve("books"));
        hostJar = Files.copy(artifact("book-server"), home.resolve("host.jar"));
        Files.copy(SAMPLES.resolve("books/sample-oss-guide.txt"), books.resolve("sample-oss-guide.txt"));
        Files.copy(SAMPLES.resolve("books").resolve(PDF), books.resolve(PDF));
    }

    @Test
    void installingAndDisablingExternalJarChangesShelfAndSearchAcrossRestarts() throws Exception {
        String originalHash = sha256(hostJar);
        try (var host = new PackagedHost(home, false)) {
            assertThat(host.get("/plugins").findValuesAsText("id")).containsExactly("builtin-txt");
            assertTxtOnly(host);
            assertFailure(host, PDF, "UNSUPPORTED_FORMAT");
        }
        Files.copy(artifact("book-plugin-pdf"), plugins.resolve("pdf.jar"));
        try (var host = new PackagedHost(home, false)) {
            var plugin = row(host.get("/plugins"), "id", "pdf-pdfbox");
            assertThat(plugin.required("status").asText()).isEqualTo("ACTIVE");
            assertThat(plugin.required("builtin").asBoolean()).isFalse();
            assertThat(plugin.required("source").asText()).isEqualTo("pdf.jar");
            assertThat(host.get("/books").findValuesAsText("format")).containsExactlyInAnyOrder("txt", "pdf");
            assertPdfReadableAndSearchable(host);
            assertTxtSearch(host);
        }
        assertThat(sha256(hostJar)).isEqualTo(originalHash);
        try (var host = new PackagedHost(home, true)) {
            var plugin = row(host.get("/plugins"), "id", "pdf-pdfbox");
            assertThat(plugin.required("status").asText()).isEqualTo("DISABLED");
            assertFailure(host, PDF, "PLUGIN_DISABLED");
            assertTxtOnly(host);
        }
        assertThat(sha256(hostJar)).isEqualTo(originalHash);
    }

    @Test
    void incompatibleAndThrowingProvidersAndBrokenPdfLeaveHealthyBooksUsable() throws Exception {
        Files.copy(artifact("book-plugin-pdf"), plugins.resolve("pdf.jar"));
        writeFaultJar(plugins.resolve("fault-fixtures.jar"));
        Files.copy(SAMPLES.resolve("corrupted/invalid-header.pdf"), books.resolve("broken.pdf"));
        Files.writeString(books.resolve("broken.fault"), "fixture");
        try (var host = new PackagedHost(home, false)) {
            var future = row(host.get("/plugins"), "id", "future-test");
            assertThat(future.required("status").asText()).isEqualTo("INCOMPATIBLE");
            assertThat(future.required("reason").asText()).contains("2.0");
            assertFailure(host, "broken.pdf", "CORRUPTED_FILE");
            assertFailure(host, "broken.fault", "PLUGIN_EXECUTION_FAILED");
            assertThat(host.get("/books").findValuesAsText("format")).containsExactlyInAnyOrder("txt", "pdf");
            assertTxtSearch(host);
            assertPdfReadableAndSearchable(host);
        }
    }

    private static void assertTxtOnly(PackagedHost host) throws Exception {
        assertThat(host.get("/books").findValuesAsText("format")).containsExactly("txt");
        assertThat(host.get(PDF_SEARCH).size()).isZero();
        assertTxtSearch(host);
    }

    private static void assertTxtSearch(PackagedHost host) throws Exception {
        String id = row(host.get("/books"), "format", "txt").required("id").asText();
        var hit = row(host.get("/rag/search?q=URLClassLoader"), "bookId", id);
        assertThat(hit.required("snippet").asText()).containsIgnoringCase("URLClassLoader");
    }

    private static void assertPdfReadableAndSearchable(PackagedHost host) throws Exception {
        var book = row(host.get("/books"), "format", "pdf");
        String id = book.required("id").asText();
        assertThat(book.required("title").asText()).isEqualTo("RAG 检索手册");
        var hit = row(host.get(PDF_SEARCH), "bookId", id);
        assertThat(hit.required("locator").asText()).isEqualTo("page:2");
        assertThat(hit.required("chapterIndex").asInt()).isOne();
        var chapter = host.get("/books/" + id + "/chapters/1");
        assertThat(chapter.required("locator")).isEqualTo(hit.required("locator"));
        assertThat(chapter.required("content").asText()).contains("蓝鹭校验词");
    }

    private static void assertFailure(PackagedHost host, String file, String expected) throws Exception {
        JsonNode failure = row(host.get("/library/status").required("failures"), "file", file);
        assertThat(failure.required("errorCode").asText()).isEqualTo(expected);
    }

    private static JsonNode row(JsonNode rows, String field, String expected) {
        for (JsonNode item : rows) {
            if (expected.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("Missing " + field + "=" + expected + " in " + rows);
    }

    private static Path artifact(String module) {
        return Path.of("..", module, "target", module + "-" + System.getProperty("bookmind.version") + ".jar");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var out = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            Files.copy(file, out);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeFaultJar(Path jar) throws Exception {
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Class<?> provider : new Class<?>[] {FaultPlugin.class, FaultPlugin.Incompatible.class}) {
                String resource = provider.getName().replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(resource));
                try (var compiled = provider.getResourceAsStream("/" + resource)) {
                    java.util.Objects.requireNonNull(compiled, resource).transferTo(out);
                }
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("META-INF/services/" + BookFormatPlugin.class.getName()));
            String providers = FaultPlugin.class.getName() + "\n" + FaultPlugin.Incompatible.class.getName() + "\n";
            out.write(providers.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}

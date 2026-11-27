package io.bookmind.server.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.bookmind.rag.RagService;
import io.bookmind.rag.RagStreamListener;
import io.bookmind.rag.model.RagAnswer;
import io.bookmind.rag.model.RagAskRequest;
import io.bookmind.rag.model.SearchHit;
import io.bookmind.server.config.BookMindProperties;
import io.bookmind.server.parser.txt.TxtBookFormatPlugin;
import io.bookmind.server.plugin.PluginManager;
import io.bookmind.spi.ParsedBook;
import io.bookmind.spi.PluginErrorCode;
import io.bookmind.spi.PluginException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibraryServiceTest {

    private static ObjectMapper createMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void scansValidTxtIsolatesCorruptAndUnsupportedFilesAndSyncsRag(@TempDir Path tempDir) throws Exception {
        Path libDir = tempDir.resolve("books");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(libDir);

        Files.writeString(libDir.resolve("guide.txt"), """
                《微服务指南》
                作者：张三

                第一章 概述
                微服务拆分原则。
                """, StandardCharsets.UTF_8);
        Files.write(libDir.resolve("broken.txt"), new byte[] { 0x00, 0x01, 0x02 });
        Files.writeString(libDir.resolve("textbook.pdf"), "%PDF-1.4 fake", StandardCharsets.UTF_8);

        BookMindProperties props = new BookMindProperties();
        props.setLibraryDir(libDir.toString());
        props.setDataDir(dataDir.toString());
        props.setPluginsDir(tempDir.resolve("empty-plugins").toString());

        RecordingRagService rag = new RecordingRagService();
        try (PluginManager pm = new PluginManager(props, List.of(new TxtBookFormatPlugin()))) {
            pm.reload();
            LibraryService service = new LibraryService(props, pm, rag, createMapper());

            ScanResult first = service.scanLibrary();
            assertThat(first.scannedCount()).isEqualTo(3);
            assertThat(first.indexedCount()).isEqualTo(1);
            assertThat(first.failures())
                    .extracting(ScanFailure::file, ScanFailure::errorCode)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("broken.txt", "CORRUPTED_FILE"),
                            org.assertj.core.groups.Tuple.tuple("textbook.pdf", "UNSUPPORTED_FORMAT")
                    );
            assertThat(rag.indexedBookIds).hasSize(1);

            // Incremental re-scan skips unchanged file
            ScanResult second = service.scanLibrary();
            assertThat(second.indexedCount()).isEqualTo(0);
            assertThat(rag.indexedBookIds).hasSize(1);

            // Restarting a fresh service instance restores persisted catalog and failure status
            LibraryService restarted = new LibraryService(props, pm, rag, createMapper());
            restarted.loadFromStorage();
            assertThat(restarted.listBooks(Optional.empty(), Optional.empty()))
                    .hasSize(1)
                    .first()
                    .extracting(BookSummary::title, BookSummary::author)
                    .containsExactly("微服务指南", "张三");
            assertThat(restarted.getStatus().failures()).hasSize(2);

            // Catalogs written by the old bootstrap service have chapters but no Lucene entries.
            Path catalog = dataDir.resolve("library-index.json");
            ObjectMapper mapper = createMapper();
            var legacy = mapper.readTree(catalog.toFile());
            ((ObjectNode) legacy.required("books").required(0)).remove("searchIndexed");
            mapper.writeValue(catalog.toFile(), legacy);
            restarted.initialize();
            assertThat(rag.indexedBookIds).hasSize(2);
            assertThat(restarted.scanLibrary().indexedCount()).isZero();
        }
    }

    @Test
    void supportsUploadFilterDeleteAndRejectsUnsafeFilenames(@TempDir Path tempDir) throws Exception {
        Path libDir = tempDir.resolve("books");
        Path dataDir = tempDir.resolve("data");
        BookMindProperties props = new BookMindProperties();
        props.setLibraryDir(libDir.toString());
        props.setDataDir(dataDir.toString());
        props.setPluginsDir(tempDir.resolve("empty-plugins").toString());

        RecordingRagService rag = new RecordingRagService();
        try (PluginManager pm = new PluginManager(props, List.of(new TxtBookFormatPlugin()))) {
            pm.reload();
            LibraryService service = new LibraryService(props, pm, rag, createMapper());

            byte[] validBytes = "《算法笔记》\n作者：李四\n\n第一章 排序\n快速排序原理。".getBytes(StandardCharsets.UTF_8);
            BookDetail uploaded = service.uploadBook("algo.txt", validBytes);

            assertThat(uploaded.title()).isEqualTo("算法笔记");
            assertThat(uploaded.chapterCount()).isEqualTo(1);
            assertThat(rag.indexedBookIds).containsExactly(uploaded.id());

            assertThat(service.listBooks(Optional.of("txt"), Optional.of("算法"))).hasSize(1);
            assertThat(service.listBooks(Optional.of("pdf"), Optional.empty())).isEmpty();
            assertThat(service.getChapter(uploaded.id(), 0).locator()).isEqualTo("chapter:1");

            // Uploading unsupported PDF fails cleanly without polluting libraryDir
            assertThatThrownBy(() -> service.uploadBook("paper.pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(PluginException.class)
                    .extracting(ex -> ((PluginException) ex).errorCode())
                    .isEqualTo(PluginErrorCode.UNSUPPORTED_FORMAT);
            assertThat(Files.exists(libDir.resolve("paper.pdf"))).isFalse();

            // Path traversal rejected
            assertThatThrownBy(() -> service.uploadBook("../evil.txt", validBytes))
                    .isInstanceOf(IllegalArgumentException.class);

            // Deleting book cleans file, shelf, and RAG index
            service.deleteBook(uploaded.id());
            assertThat(Files.exists(libDir.resolve("algo.txt"))).isFalse();
            assertThat(rag.removedBookIds).containsExactly(uploaded.id());
            assertThatThrownBy(() -> service.getBook(uploaded.id()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    private static final class RecordingRagService implements RagService {
        private final List<String> indexedBookIds = new ArrayList<>();
        private final List<String> removedBookIds = new ArrayList<>();

        @Override
        public void indexBook(String bookId, ParsedBook book) {
            indexedBookIds.add(bookId);
        }

        @Override
        public void removeBook(String bookId) {
            removedBookIds.add(bookId);
        }

        @Override
        public List<SearchHit> search(String query, Optional<String> optionalBookId, int topK) {
            return List.of();
        }

        @Override
        public RagAnswer ask(RagAskRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void askStream(RagAskRequest request, RagStreamListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}

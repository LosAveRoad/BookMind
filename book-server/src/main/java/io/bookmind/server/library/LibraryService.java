package io.bookmind.server.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bookmind.rag.RagService;
import io.bookmind.server.config.BookMindProperties;
import io.bookmind.server.plugin.PluginManager;
import io.bookmind.spi.BookCover;
import io.bookmind.spi.Chapter;
import io.bookmind.spi.ParsedBook;
import io.bookmind.spi.PluginException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Core library management service inspired by Komga.
 *
 * <p>Scans {@code bookmind.library-dir}, dispatches files through {@link PluginManager},
 * persists catalog/chapters/covers locally under {@code bookmind.data-dir}, handles web uploads
 * and deletions safely, and keeps {@link RagService} synchronized on every catalog change.
 */
@Service
public class LibraryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LibraryService.class);

    private final Path libraryDir;
    private final PluginManager pluginManager;
    private final RagService ragService;
    private final LibraryStorage storage;

    private final Map<String, StoredBookRecord> booksById = new LinkedHashMap<>();
    private List<ScanFailure> lastFailures = List.of();
    private Instant lastScanAt;

    public LibraryService(
            BookMindProperties properties,
            PluginManager pluginManager,
            RagService ragService,
            ObjectMapper objectMapper
    ) {
        this.libraryDir = Path.of(properties.getLibraryDir());
        this.pluginManager = pluginManager;
        this.ragService = ragService;
        this.storage = new LibraryStorage(Path.of(properties.getDataDir()), objectMapper);
    }

    @PostConstruct
    public synchronized void initialize() {
        loadFromStorage();
        scanLibrary();
    }

    public synchronized void loadFromStorage() {
        LibraryIndexSnapshot snapshot = storage.loadIndex();
        booksById.clear();
        for (StoredBookRecord record : snapshot.books()) {
            booksById.put(record.id(), record);
        }
        this.lastFailures = snapshot.failures();
        this.lastScanAt = snapshot.lastScanAt();
    }

    public synchronized ScanResult scanLibrary() {
        Instant now = Instant.now();
        List<ScanFailure> failures = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int scannedCount = 0;
        int indexedCount = 0;

        if (!Files.exists(libraryDir) || !Files.isDirectory(libraryDir)) {
            LOGGER.info("Library directory does not exist yet: {}", libraryDir.toAbsolutePath());
        } else {
            List<Path> files = collectLibraryFiles();
            for (Path file : files) {
                scannedCount++;
                String relativePath = normalizeRelativePath(libraryDir.relativize(file));
                String bookId = stableBookId(relativePath);
                seenIds.add(bookId);

                try {
                    String sha256 = sha256File(file);
                    StoredBookRecord existing = booksById.get(bookId);
                    boolean unchanged = existing != null
                            && existing.searchIndexed()
                            && sha256.equals(existing.sha256())
                            && storage.hasChapters(bookId)
                            && pluginManager.findActivePluginFor(file).isPresent();
                    if (unchanged) {
                        continue;
                    }

                    ParsedBook parsed = pluginManager.parse(file);
                    StoredBookRecord updated = storeParsedBook(bookId, relativePath, sha256, parsed, now);
                    booksById.put(bookId, updated);
                    indexedCount++;
                } catch (PluginException ex) {
                    failures.add(new ScanFailure(relativePath, ex.errorCode().name(), ex.getMessage()));
                    if (booksById.remove(bookId) != null) {
                        storage.deleteBookAssets(bookId);
                        ragService.removeBook(bookId);
                    }
                } catch (IOException ex) {
                    failures.add(new ScanFailure(relativePath, "IO_ERROR", ex.getMessage()));
                }
            }
        }

        int removedCount = 0;
        List<String> staleIds = booksById.keySet().stream()
                .filter(id -> !seenIds.contains(id))
                .toList();
        for (String staleId : staleIds) {
            booksById.remove(staleId);
            storage.deleteBookAssets(staleId);
            ragService.removeBook(staleId);
            removedCount++;
        }

        this.lastFailures = List.copyOf(failures);
        this.lastScanAt = now;
        persistSnapshot();

        return new ScanResult(scannedCount, indexedCount, removedCount, lastFailures, now);
    }

    public synchronized BookDetail uploadBook(String originalFileName, byte[] content) throws PluginException {
        String safeFileName = sanitizeFileName(originalFileName);
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Uploaded file content must not be empty");
        }

        Path stageDir = null;
        try {
            Files.createDirectories(libraryDir);
            stageDir = Files.createTempDirectory("bookmind-upload-");
            Path stagedFile = stageDir.resolve(safeFileName);
            Files.write(stagedFile, content);

            ParsedBook parsed = pluginManager.parse(stagedFile);
            Path targetFile = libraryDir.resolve(safeFileName).normalize();
            if (!targetFile.startsWith(libraryDir.normalize())) {
                throw new IllegalArgumentException("Invalid target filename: " + originalFileName);
            }
            Files.move(stagedFile, targetFile, StandardCopyOption.REPLACE_EXISTING);

            String relativePath = normalizeRelativePath(libraryDir.relativize(targetFile));
            String bookId = stableBookId(relativePath);
            String sha256 = sha256Bytes(content);
            Instant now = Instant.now();

            StoredBookRecord record = storeParsedBook(bookId, relativePath, sha256, parsed, now);
            booksById.put(bookId, record);
            lastFailures = lastFailures.stream()
                    .filter(f -> !f.file().equals(relativePath))
                    .toList();
            persistSnapshot();
            return record.toDetail();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to store uploaded book " + safeFileName, ex);
        } finally {
            cleanupTempDir(stageDir);
        }
    }

    public synchronized void deleteBook(String bookId) {
        StoredBookRecord existing = requireBookRecord(bookId);
        Path sourceFile = libraryDir.resolve(existing.relativePath()).normalize();
        if (sourceFile.startsWith(libraryDir.normalize())) {
            try {
                Files.deleteIfExists(sourceFile);
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to delete book file: " + existing.relativePath(), ex);
            }
        }
        booksById.remove(bookId);
        storage.deleteBookAssets(bookId);
        ragService.removeBook(bookId);
        persistSnapshot();
    }

    public synchronized List<BookSummary> listBooks(Optional<String> formatFilter, Optional<String> keywordQuery) {
        String format = formatFilter.map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).orElse("");
        String query = keywordQuery.map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).orElse("");

        return booksById.values().stream()
                .filter(b -> format.isEmpty() || b.format().equalsIgnoreCase(format))
                .filter(b -> query.isEmpty()
                        || b.title().toLowerCase(Locale.ROOT).contains(query)
                        || b.author().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(StoredBookRecord::title, String.CASE_INSENSITIVE_ORDER))
                .map(StoredBookRecord::toSummary)
                .toList();
    }

    public synchronized BookDetail getBook(String bookId) {
        return requireBookRecord(bookId).toDetail();
    }

    public synchronized List<ChapterSummary> getChapters(String bookId) {
        requireBookRecord(bookId);
        return storage.loadChapters(bookId).stream()
                .map(ch -> new ChapterSummary(ch.index(), ch.title(), ch.locator()))
                .toList();
    }

    public synchronized Chapter getChapter(String bookId, int chapterIndex) {
        requireBookRecord(bookId);
        List<Chapter> chapters = storage.loadChapters(bookId);
        return chapters.stream()
                .filter(ch -> ch.index() == chapterIndex)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CHAPTER_NOT_FOUND",
                        "Chapter index " + chapterIndex + " not found in book " + bookId
                ));
    }

    public synchronized BookCover getCover(String bookId) {
        StoredBookRecord record = requireBookRecord(bookId);
        if (!record.hasCover()) {
            throw new ResourceNotFoundException("COVER_NOT_FOUND", "Book has no cover: " + bookId);
        }
        byte[] bytes = storage.loadCoverBytes(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("COVER_NOT_FOUND", "Cover missing for: " + bookId));
        String mime = (record.coverMimeType() == null || record.coverMimeType().isBlank())
                ? "image/png" : record.coverMimeType();
        return new BookCover(mime, bytes);
    }

    public synchronized LibraryStatus getStatus() {
        return new LibraryStatus(booksById.size(), lastScanAt, lastFailures);
    }

    private StoredBookRecord storeParsedBook(
            String bookId,
            String relativePath,
            String sha256,
            ParsedBook parsed,
            Instant importedAt
    ) {
        storage.saveChapters(bookId, parsed.chapters());
        storage.saveCover(bookId, parsed.cover());
        boolean hasCover = parsed.cover().isPresent();
        String coverMime = parsed.cover().map(BookCover::mimeType).orElse("");
        ragService.indexBook(bookId, parsed);
        return new StoredBookRecord(
                bookId,
                relativePath,
                sha256,
                parsed.title(),
                parsed.author(),
                parsed.language(),
                parsed.format(),
                parsed.chapters().size(),
                hasCover,
                coverMime,
                parsed.extraMetadata(),
                importedAt,
                true
        );
    }

    private StoredBookRecord requireBookRecord(String bookId) {
        StoredBookRecord record = booksById.get(bookId);
        if (record == null) {
            throw new ResourceNotFoundException("BOOK_NOT_FOUND", "Book not found: " + bookId);
        }
        return record;
    }

    private void persistSnapshot() {
        storage.saveIndex(new LibraryIndexSnapshot(lastScanAt, List.copyOf(booksById.values()), lastFailures));
    }

    private List<Path> collectLibraryFiles() {
        try (Stream<Path> walk = Files.walk(libraryDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to walk library directory: " + libraryDir, ex);
        }
    }

    private static String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        String trimmed = originalFileName.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..") || trimmed.startsWith(".")) {
            throw new IllegalArgumentException("Unsafe filename rejected: " + originalFileName);
        }
        return trimmed;
    }

    private static String normalizeRelativePath(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String stableBookId(String relativePath) {
        return sha256Bytes(relativePath.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static String sha256File(Path file) throws IOException {
        return sha256Bytes(Files.readAllBytes(file));
    }

    private static String sha256Bytes(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    private static void cleanupTempDir(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            LOGGER.debug("Temporary upload directory cleanup skipped for {}", dir);
        }
    }
}

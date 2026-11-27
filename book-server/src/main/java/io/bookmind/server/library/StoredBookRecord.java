package io.bookmind.server.library;

import java.time.Instant;
import java.util.Map;

/**
 * Persisted library catalog record stored inside {@code data/library-index.json}.
 */
record StoredBookRecord(
        String id,
        String relativePath,
        String sha256,
        String title,
        String author,
        String language,
        String format,
        int chapterCount,
        boolean hasCover,
        String coverMimeType,
        Map<String, String> extraMetadata,
        Instant importedAt,
        boolean searchIndexed
) {
    StoredBookRecord {
        extraMetadata = extraMetadata == null ? Map.of() : Map.copyOf(extraMetadata);
    }

    BookSummary toSummary() {
        return new BookSummary(id, title, author, language, format, chapterCount, hasCover);
    }

    BookDetail toDetail() {
        return new BookDetail(
                id, title, author, language, format, chapterCount, hasCover,
                extraMetadata, relativePath, importedAt
        );
    }
}

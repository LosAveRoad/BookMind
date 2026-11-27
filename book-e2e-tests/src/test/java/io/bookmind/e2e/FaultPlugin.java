package io.bookmind.e2e;

import io.bookmind.spi.BookFormatPlugin;
import io.bookmind.spi.ParsedBook;
import io.bookmind.spi.PluginMetadata;
import io.bookmind.spi.SpiVersion;

import java.nio.file.Path;
import java.util.Set;

/** Providers packaged into an external fixture JAR and discovered by the host via ServiceLoader. */
public class FaultPlugin implements BookFormatPlugin {
    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata("throwing-test", "Throwing fixture", "1.0", SpiVersion.CURRENT_API_VERSION, "", "");
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("fault");
    }

    @Override
    public ParsedBook parse(Path path) {
        throw new IllegalStateException("Intentional parse failure");
    }

    public static final class Incompatible extends FaultPlugin {
        @Override
        public PluginMetadata metadata() {
            return new PluginMetadata("future-test", "Future SPI fixture", "1.0", "2.0", "", "");
        }
    }
}

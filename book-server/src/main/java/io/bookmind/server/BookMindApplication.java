package io.bookmind.server;

import io.bookmind.rag.DefaultRagService;
import io.bookmind.rag.OpenAiConfig;
import io.bookmind.rag.RagEngineFactory;
import io.bookmind.rag.RagService;
import io.bookmind.server.config.BookMindProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

@SpringBootApplication
@EnableConfigurationProperties(BookMindProperties.class)
public class BookMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookMindApplication.class, args);
    }

    @Bean
    @ConditionalOnMissingBean(RagService.class)
    public DefaultRagService ragService(BookMindProperties properties) throws IOException {
        var rag = properties.getRag();
        var config = new OpenAiConfig(rag.getBaseUrl(), rag.getApiKey(), rag.getModel(),
                Duration.ofSeconds(rag.getTimeoutSeconds()));
        return RagEngineFactory.create(Path.of(properties.getDataDir(), "lucene-index"), config);
    }
}

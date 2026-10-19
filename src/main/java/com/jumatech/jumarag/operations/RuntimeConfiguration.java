package com.jumatech.jumarag.operations;

import com.jumatech.jumarag.ingestion.IngestionProperties;
import com.jumatech.jumarag.answer.AnswerProperties;
import com.jumatech.jumarag.provider.ProviderProperties;
import com.jumatech.jumarag.retrieval.RetrievalProperties;
import com.jumatech.jumarag.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    RuntimeConfiguration.RuntimeProperties.class,
    SecurityProperties.class,
    IngestionProperties.class,
    AnswerProperties.class,
    ProviderProperties.class,
    RetrievalProperties.class
})
public class RuntimeConfiguration {
    private static final Logger log = LoggerFactory.getLogger(RuntimeConfiguration.class);

    @Bean
    ApplicationRunner runtimeStarted(RuntimeProperties properties) {
        return arguments -> log.info("JumaRAG runtime started runtimeMode={}", properties.runtime());
    }

    @ConfigurationProperties(prefix = "app")
    public record RuntimeProperties(RuntimeMode runtime) {
        public RuntimeProperties {
            runtime = runtime == null ? RuntimeMode.API : runtime;
        }
    }
}

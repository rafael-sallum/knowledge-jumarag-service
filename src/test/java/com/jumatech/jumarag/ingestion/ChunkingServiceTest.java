package com.jumatech.jumarag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChunkingServiceTest {
    private final ChunkingService chunking = new ChunkingService(
            new IngestionProperties(1_000_000, 20, 30, 5, "phase0-fake-1536"),
            new ObjectMapper());

    @Test
    void isDeterministicOrderedBoundedAndOverlapping() {
        String words = IntStream.range(0, 48)
                .mapToObj(index -> "palavra" + index)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();

        var first = chunking.chunk(words, false);
        var second = chunking.chunk(words, false);

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(ChunkingService.GeneratedChunk::index).containsExactly(0, 1, 2);
        assertThat(first).allSatisfy(chunk -> assertThat(chunk.text().split("\\s+")).hasSizeLessThanOrEqualTo(30));
        assertThat(first.get(0).text().split("\\s+")[15])
                .isEqualTo(first.get(1).text().split("\\s+")[0]);
    }

    @Test
    void preservesMarkdownHeadingPathInTextAndMetadata() {
        var chunks = chunking.chunk("# Snooker\n\nIntrodução.\n\n## Bola livre\n\nRegra aplicável após falta.", true);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).headingPath()).isEqualTo("Snooker");
        assertThat(chunks.get(1).headingPath()).isEqualTo("Snooker > Bola livre");
        assertThat(chunks.get(1).text()).startsWith("Snooker > Bola livre\n\n");
        assertThat(chunks.get(1).metadata().get("wordStart").asInt()).isZero();
    }

    @Test
    void prefersParagraphBoundariesWithinTheHardMaximum() {
        String paragraph = IntStream.range(0, 12)
                .mapToObj(index -> "p" + index)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();

        var chunks = chunking.chunk(paragraph + "\n\n" + paragraph + "\n\n" + paragraph, false);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().metadata().get("wordEnd").asInt()).isEqualTo(24);
        assertThat(chunks.getFirst().text().split("\\s+")).hasSizeLessThanOrEqualTo(30);
    }
}

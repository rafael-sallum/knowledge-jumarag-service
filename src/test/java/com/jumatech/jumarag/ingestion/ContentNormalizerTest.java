package com.jumatech.jumarag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class ContentNormalizerTest {
    private final ContentNormalizer normalizer = new ContentNormalizer();

    @Test
    void producesStableChecksumAcrossLineEndingsTrailingSpacesAndUnicodeComposition() {
        String decomposed = Normalizer.normalize("Regra da bola livre", Normalizer.Form.NFD);
        var first = normalizer.normalize("# Título\r\n\r\n" + decomposed + "   \r\n\r\n\r\n");
        var second = normalizer.normalize("# Título\n\nRegra da bola livre\n");

        assertThat(first.text()).isEqualTo(second.text());
        assertThat(first.checksum()).isEqualTo(second.checksum()).hasSize(64);
        assertThat(first.byteSize()).isEqualTo(second.byteSize());
    }

    @Test
    void preservesMeaningfulParagraphAndHeadingStructure() {
        var result = normalizer.normalize("# Regra\n\nPrimeiro parágrafo.\n\nSegundo parágrafo.");

        assertThat(result.text()).isEqualTo("# Regra\n\nPrimeiro parágrafo.\n\nSegundo parágrafo.");
    }
}

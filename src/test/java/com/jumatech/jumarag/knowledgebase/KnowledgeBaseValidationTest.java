package com.jumatech.jumarag.knowledgebase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jumatech.jumarag.shared.web.DomainException;
import org.junit.jupiter.api.Test;

class KnowledgeBaseValidationTest {

    @Test
    void validatesPortableSlugsAndBcp47Locales() {
        assertThatCode(() -> KnowledgeBaseService.validateSlug("salloon-snooker-rules"))
                .doesNotThrowAnyException();
        assertThatCode(() -> KnowledgeBaseService.validateLocale("pt-BR"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> KnowledgeBaseService.validateSlug("Salloon rules"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> KnowledgeBaseService.validateLocale(""))
                .isInstanceOf(DomainException.class);
    }
}

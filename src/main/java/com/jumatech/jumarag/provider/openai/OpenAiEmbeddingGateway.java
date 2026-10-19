package com.jumatech.jumarag.provider.openai;

import com.jumatech.jumarag.embedding.EmbeddingGateway;
import com.jumatech.jumarag.provider.ProviderCallExecutor;
import com.jumatech.jumarag.provider.ProviderException;
import org.springframework.ai.embedding.EmbeddingModel;

public final class OpenAiEmbeddingGateway implements EmbeddingGateway {
    private final EmbeddingModel model;
    private final ProviderCallExecutor calls;
    private final String modelName;
    private final int dimensions;

    public OpenAiEmbeddingGateway(
            EmbeddingModel model, ProviderCallExecutor calls, String modelName, int dimensions) {
        this.model = model;
        this.calls = calls;
        this.modelName = modelName;
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding text must not be blank.");
        }
        return calls.execute(provider(), model(), ProviderCallExecutor.Operation.EMBEDDING, () -> {
            float[] vector = model.embed(text);
            if (vector == null || vector.length != dimensions) {
                throw new ProviderException(ProviderException.Reason.INCOMPATIBLE_VECTOR);
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new ProviderException(ProviderException.Reason.INCOMPATIBLE_VECTOR);
                }
            }
            return vector;
        });
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String provider() {
        return "OPENAI";
    }

    @Override
    public String model() {
        return modelName;
    }
}

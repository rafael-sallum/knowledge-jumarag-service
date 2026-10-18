package com.jumatech.jumarag.embedding;

public interface EmbeddingGateway {
    float[] embed(String text);

    int dimensions();

    String provider();

    String model();
}

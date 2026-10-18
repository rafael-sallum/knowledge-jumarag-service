package com.jumatech.jumarag.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public final class ContentNormalizer {

    public NormalizedContent normalize(String content) {
        if (content == null) {
            throw new IllegalArgumentException("Content is required.");
        }
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        StringBuilder cleaned = new StringBuilder(normalized.length());
        int consecutiveBlankLines = 0;
        for (String line : normalized.split("\n", -1)) {
            String withoutTrailingWhitespace = line.replaceFirst("[ \\t]+$", "");
            boolean blank = withoutTrailingWhitespace.isBlank();
            if (blank) {
                consecutiveBlankLines++;
                if (consecutiveBlankLines > 2) {
                    continue;
                }
            } else {
                consecutiveBlankLines = 0;
            }
            if (!cleaned.isEmpty()) {
                cleaned.append('\n');
            }
            cleaned.append(withoutTrailingWhitespace);
        }
        String value = cleaned.toString().strip();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new NormalizedContent(value, sha256(bytes), bytes.length);
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    public record NormalizedContent(String text, String checksum, long byteSize) {}
}

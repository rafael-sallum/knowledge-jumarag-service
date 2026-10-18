package com.jumatech.jumarag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public final class ChunkingService {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private final IngestionProperties properties;
    private final ObjectMapper objectMapper;

    public ChunkingService(IngestionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<GeneratedChunk> chunk(String content, boolean markdown) {
        List<Section> sections = sections(content, markdown);
        List<GeneratedChunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            SectionWords sectionWords = words(section.body());
            List<String> words = sectionWords.words();
            if (words.isEmpty()) {
                continue;
            }
            int start = 0;
            while (start < words.size()) {
                int end = chooseEnd(start, words.size(), sectionWords.paragraphEnds());
                String body = String.join(" ", words.subList(start, end));
                String text = section.headingPath() == null
                        ? body
                        : section.headingPath() + "\n\n" + body;
                JsonNode metadata = objectMapper.createObjectNode()
                        .put("wordStart", start)
                        .put("wordEnd", end)
                        .put("overlapWords", start == 0 ? 0 : properties.overlapWords());
                chunks.add(new GeneratedChunk(
                        chunks.size(),
                        text,
                        section.headingPath(),
                        ContentNormalizer.sha256(text.getBytes(StandardCharsets.UTF_8)),
                        metadata));
                if (end == words.size()) {
                    break;
                }
                start = end - properties.overlapWords();
            }
        }
        return List.copyOf(chunks);
    }

    private int chooseEnd(int start, int totalWords, List<Integer> paragraphEnds) {
        int target = Math.min(start + properties.targetWords(), totalWords);
        int maximum = Math.min(start + properties.maxWords(), totalWords);
        int earlierBoundary = -1;
        for (int boundary : paragraphEnds) {
            if (boundary <= start) {
                continue;
            }
            if (boundary >= target && boundary <= maximum) {
                return boundary;
            }
            if (boundary < target) {
                earlierBoundary = boundary;
            }
        }
        return earlierBoundary > start + properties.overlapWords() ? earlierBoundary : target;
    }

    private static SectionWords words(String body) {
        List<String> words = new ArrayList<>();
        List<Integer> paragraphEnds = new ArrayList<>();
        for (String paragraph : body.split("\\n\\s*\\n")) {
            Arrays.stream(paragraph.split("\\s+"))
                    .filter(value -> !value.isBlank())
                    .forEach(words::add);
            if (!words.isEmpty()) {
                paragraphEnds.add(words.size());
            }
        }
        return new SectionWords(List.copyOf(words), List.copyOf(paragraphEnds));
    }

    private static List<Section> sections(String content, boolean markdown) {
        if (!markdown) {
            return List.of(new Section(null, content));
        }
        List<Section> sections = new ArrayList<>();
        String[] headings = new String[6];
        String headingPath = null;
        StringBuilder body = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                addSection(sections, headingPath, body);
                int level = heading.group(1).length();
                headings[level - 1] = heading.group(2).strip();
                Arrays.fill(headings, level, headings.length, null);
                headingPath = Arrays.stream(headings)
                        .filter(value -> value != null)
                        .reduce((left, right) -> left + " > " + right)
                        .orElse(null);
            } else {
                if (!body.isEmpty()) {
                    body.append('\n');
                }
                body.append(line);
            }
        }
        addSection(sections, headingPath, body);
        return sections.isEmpty() ? List.of(new Section(null, content)) : sections;
    }

    private static void addSection(List<Section> sections, String headingPath, StringBuilder body) {
        String value = body.toString().strip();
        if (!value.isBlank()) {
            sections.add(new Section(headingPath, value));
        }
        body.setLength(0);
    }

    private record Section(String headingPath, String body) {}

    private record SectionWords(List<String> words, List<Integer> paragraphEnds) {}

    public record GeneratedChunk(
            int index,
            String text,
            String headingPath,
            String checksum,
            JsonNode metadata) {}
}

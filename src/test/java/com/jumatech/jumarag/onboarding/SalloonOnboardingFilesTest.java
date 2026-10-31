package com.jumatech.jumarag.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SalloonOnboardingFilesTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path ONBOARDING = ROOT.resolve("knowledge/onboarding/salloon");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> FORBIDDEN_CONTEXT_KEYS = Set.of(
            "userid", "playerid", "auth0subject", "email", "phone", "phonenumber", "deviceid", "name", "handle");
    private static final Set<String> EVIDENCE_INTENTS = Set.of(
            "GENERAL", "OFFICIAL_ONLY", "IMPLEMENTATION_ONLY", "OFFICIAL_AND_IMPLEMENTATION");

    @Test
    void officialManifestsAreControlledAndPointOnlyToVerifiedAuthorities() throws IOException {
        List<Path> manifests = List.of(
                ONBOARDING.resolve("snooker/sources/wpbsa-rulebook-2024-25.json"),
                ONBOARDING.resolve("brazilian/sources/cbbs-sinuca-2012.json"),
                ONBOARDING.resolve("brazilian/sources/cbbs-billiards-regulation-2013.json"));

        for (Path manifest : manifests) {
            JsonNode node = JSON.readTree(manifest.toFile());
            assertThat(node.path("schemaVersion").asText()).isEqualTo("jumarag.source-manifest.v1");
            assertThat(node.path("sourceClassification").asText()).isEqualTo("OFFICIAL_RULE");
            assertThat(node.path("sha256").asText()).matches(SHA256);
            assertThat(node.path("retrievedAt").asText()).isEqualTo("2026-10-31");
            assertThat(node.path("localPreparedFile").asText()).startsWith(".local/knowledge-sources/");
            assertThat(node.path("rights").asText()).containsIgnoringCase("must not be committed");
            String url = node.path("canonicalUrl").asText();
            assertThat(url).matches("https://(www\\.)?(wpbsa\\.com|snookercbbs\\.com)/.*");
        }
        assertThat(Files.readString(ROOT.resolve(".gitignore"))).contains(".local/");
    }

    @Test
    void goldenCorpusIsMeaningfulAndEvidenceOriented() throws IOException {
        List<Path> files = List.of(
                ONBOARDING.resolve("snooker/evaluation/golden-cases.json"),
                ONBOARDING.resolve("brazilian/evaluation/golden-cases.json"));
        Set<String> ids = new HashSet<>();
        int total = 0;
        int implementationCases = 0;
        int comparisonCases = 0;
        for (Path file : files) {
            JsonNode root = JSON.readTree(file.toFile());
            assertThat(root.path("schemaVersion").asText()).isEqualTo("jumarag.golden-cases.v1");
            assertThat(root.path("knowledgeBaseSlug").asText()).startsWith("salloon-");
            for (JsonNode golden : root.path("cases")) {
                total++;
                assertThat(ids.add(golden.path("id").asText())).isTrue();
                assertThat(golden.path("question").asText()).isNotBlank();
                assertThat(golden.path("expectedTopic").asText()).isNotBlank();
                String evidenceIntent = golden.path("evidenceIntent").asText();
                assertThat(EVIDENCE_INTENTS).contains(evidenceIntent);
                assertThat(Set.of("ANSWERED", "INSUFFICIENT_EVIDENCE", "CONFLICTING_SOURCES"))
                        .contains(golden.path("expectedStatus").asText());
                boolean requiresImplementation = golden.path("requireImplementationEvidence").asBoolean();
                boolean requiresOfficial = golden.path("requireOfficialEvidence").asBoolean();
                if (requiresImplementation) implementationCases++;
                if (requiresImplementation && requiresOfficial) comparisonCases++;
                if (requiresImplementation && requiresOfficial) {
                    assertThat(evidenceIntent).isEqualTo("OFFICIAL_AND_IMPLEMENTATION");
                } else if (requiresImplementation) {
                    assertThat(evidenceIntent).isEqualTo("IMPLEMENTATION_ONLY");
                } else if (requiresOfficial) {
                    assertThat(evidenceIntent).isEqualTo("OFFICIAL_ONLY");
                }
            }
        }
        assertThat(total).isBetween(25, 40);
        assertThat(implementationCases).isGreaterThanOrEqualTo(8);
        assertThat(comparisonCases).isGreaterThanOrEqualTo(4);
    }

    @Test
    void contextSchemaIsSeatBasedAndContainsNoPiiFields() throws IOException {
        JsonNode schema = JSON.readTree(ONBOARDING.resolve("context/salloon-match-context-v1.schema.json").toFile());
        assertThat(schema.path("properties").path("schemaVersion").path("const").asText())
                .isEqualTo("salloon.match-context.v1");
        Set<String> propertyNames = new HashSet<>();
        collectPropertyNames(schema, propertyNames);
        assertThat(propertyNames).contains("rulesetFamily", "rulesetId", "activePlayerSlot", "ballOn");
        assertThat(propertyNames.stream().map(String::toLowerCase).toList())
                .doesNotContainAnyElementsOf(FORBIDDEN_CONTEXT_KEYS);
        assertThat(schema.toString()).contains("PLAYER_1", "PLAYER_2");
    }

    @Test
    void implementationNotesKeepOfficialAndProductClaimsSeparate() throws IOException {
        String notes = Files.readString(ONBOARDING.resolve(
                "snooker/implementation/salloon-snooker-implementation-notes.md"))
                + Files.readString(ONBOARDING.resolve(
                        "brazilian/implementation/salloon-brazilian-implementation-notes.md"));
        assertThat(notes).contains("IMPLEMENTATION_NOTE", "MATCHES_OFFICIAL_RULE", "SIMPLIFIES_OFFICIAL_RULE",
                "SPECIALIZES_OFFICIAL_RULE", "DIFFERS_FROM_OFFICIAL_RULE", "REQUIRES_REVIEW");
        assertThat(notes).contains("packages/sporting-engine", "saloon-ruleset.test.ts", "respotted-black.test.ts");
    }

    @Test
    void operationalScriptsAreStrictAndNeverEmbedCredentials() throws IOException {
        for (String name : List.of("prepare-sources.sh", "bootstrap.sh", "compare-strategies.sh")) {
            String script = Files.readString(ROOT.resolve("ops/onboarding/salloon/" + name));
            assertThat(script).contains("set -euo pipefail");
            assertThat(script).doesNotContain("OPENAI_API_KEY=", "JUMARAG_ADMIN_TOKEN=", "JUMARAG_CLIENT_KEY=");
        }
        String bootstrap = Files.readString(ROOT.resolve("ops/onboarding/salloon/bootstrap.sh"));
        assertThat(bootstrap).contains("resolve_kb", "ensure_snapshot", "activate_snapshot");
    }

    @Test
    void plannedSalloonClientHasOnlyAskGrantsAndNoCredential() throws IOException {
        JsonNode profile = JSON.readTree(ONBOARDING.resolve("integration/client-profile.json").toFile());
        assertThat(profile.path("name").asText()).isEqualTo("Salloon Score Backend");
        assertThat(profile.path("credentialProvisioned").asBoolean()).isFalse();
        assertThat(profile.path("grants").size()).isEqualTo(2);
        for (JsonNode grant : profile.path("grants")) {
            assertThat(grant.path("permission").asText()).isEqualTo("ASK");
            assertThat(grant.path("knowledgeBaseSlug").asText())
                    .isIn("salloon-snooker-rules", "salloon-brazilian-rules");
        }
        assertThat(profile.toString()).doesNotContain("jrag_", "secret", "OPENAI_API_KEY");
    }

    private static void collectPropertyNames(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            JsonNode properties = node.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(names::add);
            }
            node.elements().forEachRemaining(child -> collectPropertyNames(child, names));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectPropertyNames(child, names));
        }
    }
}

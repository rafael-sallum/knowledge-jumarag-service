# Phase 4B calibration results

Calibration was performed on 2026-10-31 against immutable development snapshots built from the reviewed manifests and first-party implementation notes. The generated JSONL reports and third-party corpus remain under ignored `.local/knowledge-sources`; this file records only safe aggregate results.

## Corpus and snapshots

| Knowledge Base | Snapshot contents | Chunks |
| --- | --- | ---: |
| `salloon-snooker-rules` | WPBSA September 2024 rulebook + Salloon Snooker implementation notes | 101 |
| `salloon-brazilian-rules` | CBBS Sinuca 2012 + CBBS Billiards Regulation 2013 + Salloon Brazilian implementation notes | 34 |

All 135 chunks received the explicit `openai-text-embedding-3-small-1536` embedding profile. The existing fake profile remains present and isolated.

## Deterministic/fake-provider comparison

A bounded four-case sample per Knowledge Base exercised all four context strategies.

- `LONG_CONTEXT` correctly rejected both snapshots with `LONG_CONTEXT_BUDGET_EXCEEDED`; it did not silently truncate the official corpus.
- Snooker `AUTO` selected `RAG` for three focused cases and `HYBRID` once when the retrieval signal required expansion.
- Brazilian `AUTO` used compact retrieval/context while preserving valid citations.
- `HYBRID` improved source-classification coverage for several focused official-rule questions, at the expected cost of more chunks and estimated tokens.

These structural checks use deterministic fake chat and are not claims about generated prose quality.

## Small real-provider comparison

One official-versus-implementation question per Knowledge Base was run through `RAG`, `HYBRID`, and `AUTO`. `LONG_CONTEXT` was not sent to the provider because the local budget check had already rejected it.

| Knowledge Base | Strategy | Used | Chunks | Estimated context tokens | Citation classes | Chat input/output tokens |
| --- | --- | --- | ---: | ---: | --- | ---: |
| Snooker | RAG | RAG | 6 | 1,112 | implementation + official | 1,364 / 114 |
| Snooker | HYBRID | HYBRID | 12 | 2,168 | implementation + official | 2,523 / 137 |
| Snooker | AUTO | RAG | 6 | 1,112 | implementation + official | 1,364 / 113 |
| Brazilian | RAG | RAG | 6 | 969 | implementation only | 1,382 / 107 |
| Brazilian | HYBRID | HYBRID | 8 | 1,233 | implementation only | 1,731 / 89 |
| Brazilian | AUTO | RAG | 6 | 969 | implementation only | 1,382 / 104 |

All six answers were `ANSWERED`, used only valid supplied citation IDs, and remained bound to the active snapshot and explicit embedding profile. Total chat usage was 9,746 input tokens and 664 output tokens. At the published `gpt-4.1-mini` rates current on the calibration date ($0.40/M input and $1.60/M output), the measured chat portion was approximately **US$0.00496**. Corpus creation made 135 embedding requests; the six strategy runs made six query-embedding requests. The current adapter does not expose exact embedding token usage, so no embedding-cost estimate is presented as measured cost. See the official [GPT-4.1 Mini model page](https://developers.openai.com/api/docs/models/gpt-4.1-mini) and [text-embedding-3-small model page](https://developers.openai.com/api/docs/models/text-embedding-3-small).

The model page now marks `gpt-4.1-mini` deprecated. This does not invalidate the bounded calibration, but production onboarding must select and re-evaluate a supported configurable chat model before deployment.

## Phase 4C evidence-intent release gate

Phase 4C keeps the Phase 4B rows above as the before-state. The Brazilian comparative question previously returned implementation-only evidence under RAG/HYBRID/AUTO. With explicit `OFFICIAL_AND_IMPLEMENTATION`, JumaRAG now reuses one query embedding, performs classification-bounded retrieval, and requires relevant evidence plus citations from both groups before generation.

The deterministic Salloon-corpus smoke produced:

| Case | Intent | Strategy used | Present/cited classes | Diversity | Status |
| --- | --- | --- | --- | --- | --- |
| Brazilian official | `OFFICIAL_ONLY` | RAG | official | true | ANSWERED |
| Brazilian implementation | `IMPLEMENTATION_ONLY` | RAG | implementation | true | ANSWERED |
| Brazilian comparison | `OFFICIAL_AND_IMPLEMENTATION` | RAG | official + implementation | true | ANSWERED |
| Brazilian comparison | `OFFICIAL_AND_IMPLEMENTATION` | AUTO → RAG | official + implementation | true | ANSWERED |
| Controlled comparison without official source | `OFFICIAL_AND_IMPLEMENTATION` | RAG | implementation only | false | INSUFFICIENT_EVIDENCE |

The missing-side case made no chat call. Integration tests exercise the same comparative invariant through RAG, LONG_CONTEXT, HYBRID, and AUTO. All 30 golden cases now declare an allowlisted evidence intent.

## Supported-model regression

The development default moved to configurable `gpt-6-luna`, documented by OpenAI as its most efficient model for focused, high-volume tasks and supporting structured outputs. Four bounded real chat calls used the existing Salloon corpus and fake query embeddings; no paid embedding call or corpus re-embedding was needed for this regression.

| Case | Intent | Chunks | Citation classes | Input/output tokens | Result |
| --- | --- | ---: | --- | ---: | --- |
| Snooker Free Ball official | `OFFICIAL_ONLY` | 6 | official | 2,631 / 96 | ANSWERED |
| Snooker Free Ball in Salloon | `IMPLEMENTATION_ONLY` | 6 | implementation | 1,062 / 117 | ANSWERED |
| Brazilian refusal official | `OFFICIAL_ONLY` | 2 | official | 2,697 / 105 | ANSWERED |
| Brazilian refusal comparison | `OFFICIAL_AND_IMPLEMENTATION` | 2 | official + implementation | 1,644 / 99 | ANSWERED |

Total measured chat usage was 8,034 input and 417 output tokens across four calls. At the standard short-context rates published on 2026-10-31 for `gpt-6-luna` ($0.10/M input, $0.50/M output), the estimated chat cost is approximately **US$0.00101**. This is an estimate from measured tokens and published rates, not an invoice. See the official [GPT-6 Luna model page](https://developers.openai.com/api/docs/models/gpt-6-luna) and [OpenAI API pricing](https://developers.openai.com/api/docs/pricing).

## Recommendation

- Start `salloon-snooker-rules` with `AUTO`. On the representative comparison question it chose compact `RAG`, retained both official and implementation evidence, and used substantially less context than `HYBRID`.
- Keep `salloon-brazilian-rules` on `AUTO`. The Phase 4C release gate now enforces and demonstrated official plus implementation evidence/citations for the refusal/pass-back comparison. Missing either side fails locally as insufficient evidence.
- Keep the current AUTO threshold unchanged. It selected `RAG` for strong real retrieval signals and demonstrated the `HYBRID` branch in the deterministic sample without duplicate query embeddings.
- Do not use `LONG_CONTEXT` for either initial corpus under the current conservative budget.

The next phase may integrate the Salloon backend behind its feature flag and trusted KB/intent routing. The PWA must never select arbitrary KBs, models, strategies, or source classes.

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

## Recommendation

- Start `salloon-snooker-rules` with `AUTO`. On the representative comparison question it chose compact `RAG`, retained both official and implementation evidence, and used substantially less context than `HYBRID`.
- Keep `salloon-brazilian-rules` on `AUTO` for development calibration, but do not onboard production comparison questions until source-classification coverage is enforced or improved. None of the three real runs cited the complementary official rule for the refusal/pass-back comparison. This is an evaluation gate, not a reason to fabricate an official citation.
- Keep the current AUTO threshold unchanged. It selected `RAG` for strong real retrieval signals and demonstrated the `HYBRID` branch in the deterministic sample without duplicate query embeddings.
- Do not use `LONG_CONTEXT` for either initial corpus under the current conservative budget.

Future onboarding should add an explicit official-versus-implementation evidence-diversity policy or query-intent filter before the Salloon backend exposes comparative answers. That belongs in the integration phase; Phase 4B does not alter generic retrieval semantics to fit one client.

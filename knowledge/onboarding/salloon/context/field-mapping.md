# `salloon.match-context.v1` field policy

The DTO is a proposed contract for the next integration phase. It contains deterministic sporting projection data only. It excludes names, handles, email, phone, Auth0 subjects, database user/player IDs, device IDs, notification addresses and free-form event descriptions.

| Field | Classification | Reason |
|---|---|---|
| `schemaVersion` | ANSWER_CONTEXT | Contract validation and prompt labeling; never SQL. |
| `rulesetFamily` | BOTH | Selects the Snooker or Brazilian Knowledge Base and explains the state. The backend maps the allowlisted enum to a KB; JumaRAG does not accept it as arbitrary SQL. |
| `rulesetId` | BOTH | Allowlisted profile/ruleset metadata filter and answer context. |
| `profile` | BOTH | Distinguishes Traditional, Six Reds, club-reduced and Brazilian compatibility profiles. |
| `matchStatus` | ANSWER_CONTEXT | Helps explain whether the state is active/completed. |
| `matchFormat` | ANSWER_CONTEXT | Useful for frame/match lifecycle explanations. |
| `frame.status` | ANSWER_CONTEXT | Current deterministic frame lifecycle. |
| `frame.phase` | ANSWER_CONTEXT | Current engine phase, not a retrieval SQL expression. |
| `frame.activePlayerSlot` | ANSWER_CONTEXT | Uses `PLAYER_1`/`PLAYER_2`, never an identity. |
| `frame.startingPlayerSlot` | ANSWER_CONTEXT | Seat-only lifecycle context. |
| `frame.scores` | ANSWER_CONTEXT | Necessary for penalty-points/endgame explanations. |
| `frame.currentBreakScore` | ANSWER_CONTEXT | Snooker break explanation only. |
| `frame.ballOn` | ANSWER_CONTEXT | Direct answer context for “what is on now?”. |
| `frame.redsRemaining` | ANSWER_CONTEXT | Snooker phase/points-remaining explanation. |
| `frame.coloursOnTable` | ANSWER_CONTEXT | Logical state, not physical coordinates. |
| Brazilian available/removed/risky/Premium balls | ANSWER_CONTEXT | Explains deterministic Brazilian phase and options. |
| `frame.cueBallPlacement` | ANSWER_CONTEXT | Explicit engine fact (`IN_D`/`ANYWHERE`), not geometry inference. |
| `frame.freeBall` | ANSWER_CONTEXT | Explicit operator-recorded state only. |
| `frame.missSequence` | ANSWER_CONTEXT | Explicit policy/status counters; no player identity. |

Only `rulesetFamily`, `rulesetId` and `profile` may become retrieval filters, through a server-owned allowlist and source/chunk metadata defined during ingestion. All other fields remain untrusted answer context and must never become dynamic SQL.


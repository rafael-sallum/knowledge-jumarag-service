# Salloon Score Snooker implementation notes

Classification: `IMPLEMENTATION_NOTE`

These notes describe the deterministic Salloon Score sporting engine. They are not official WPBSA rules. The authoritative source in the same Knowledge Base is the separately ingested WPBSA rulebook. Code paths are relative to the read-only `salloon-score` repository at revision inspected on 2026-10-31.

## Supported profiles

- **Salloon behavior:** three immutable profiles exist: Traditional with 15 Reds (`snooker-wpbsa-2024-v1`), official Six Reds with 6 Reds (`snooker-six-red-wpbsa-2024-v1`), and a club-reduced profile with 3 Reds (`snooker-club-reduced-v1`).
- **Evidence:** `packages/sporting-engine/src/domain/rulesets/snooker/profile.ts`; `snooker.test.ts`; `six-red.test.ts`.
- **Assessment:** `SPECIALIZES_OFFICIAL_RULE` for Traditional/Six Reds; `DIFFERS_FROM_OFFICIAL_RULE` for the club profile.

## Normal Red/Colour progression

- **Salloon behavior:** play begins with Red on. A legal Red pot removes the recorded number of Reds, then a nominated Colour is on. Colours potted after Reds are respotted logically. After the last Red, one nominated Colour precedes the ordered final Colours Yellow through Black. A final-Colour miss keeps that Colour on and transfers the turn.
- **Evidence:** `snooker.test.ts` tests normal Red/Colour play, the post-last-Red Colour and final Colours.
- **Assessment:** `MATCHES_OFFICIAL_RULE` for the modeled paths.

## Foul penalty derivation

- **Salloon behavior:** typed foul facts derive a single 4–7 point penalty using the highest applicable value; simultaneous infringements are not added together. Penalty points go only to the non-offender and never inflate the offender's break.
- **Evidence:** `fouls.ts#calculateSnookerFoulPenalty`; `fouls.test.ts`.
- **Assessment:** `MATCHES_OFFICIAL_RULE` for the covered facts.

## Foul and a Miss is an explicit human fact

- **Salloon behavior:** the engine never infers geometry, effort, whether a player is snookered, or whether a Miss should be called. An operator records the referee facts. Restoration preserves the foul penalty and makes the same offender replay from the stored logical original position.
- **Evidence:** `foul-and-miss-commands.ts`; `traditional-miss-policy.ts`; `foul-and-miss.test.ts`; `traditional-miss.test.ts`.
- **Assessment:** `SIMPLIFIES_OFFICIAL_RULE` operationally while retaining explicit referee decisions.

## Traditional repeated-Miss procedure

- **Salloon behavior:** only the Traditional `OFFICIAL` policy supports the warning/frame-award sequence. The second qualifying Miss does not warn until the non-offender requests restoration again; the subsequent qualifying failure from that restored position awards the frame. Penalty-points and impossible-contact exceptions depend on explicit referee facts. No artificial score is added for the frame award.
- **Applicability:** `snooker-wpbsa-2024-v1` only.
- **Evidence:** `traditional-miss.test.ts`, including the warning, frame-award and exception tests.
- **Assessment:** `SPECIALIZES_OFFICIAL_RULE` through explicit, auditable inputs.

## Official Six Reds Miss limit

- **Salloon behavior:** the fourth consecutive restored Miss issues a warning and still permits Return four. The fifth attempt reaches the limit. The non-offender may accept the position, require the offender to play from it, or take the cue ball in hand anywhere when the penalty-points exception does not apply.
- **Applicability:** `snooker-six-red-wpbsa-2024-v1` only.
- **Evidence:** `six-red-policy.ts`; `six-red.test.ts`.
- **Assessment:** `MATCHES_OFFICIAL_RULE` for the implemented WPBSA 2024-25 Section 3 Rule 19 paths.

## Club Return limits

- **Salloon behavior:** the 3-Red club profile accepts a configured Return limit from 1–5 or unlimited. After a finite limit is exhausted, the response can include cue ball in hand in the D. This policy is distinct from official Traditional and Six Reds.
- **Evidence:** `return-policy.ts`; `foul-and-miss.test.ts` club-limit tests.
- **Assessment:** `DIFFERS_FROM_OFFICIAL_RULE` (product/club rule).

## Free Ball

- **Salloon behavior:** a foul opens a decision-recording window, but Salloon does not infer the geometrical entitlement. Award and nomination are explicit human actions. The nominee takes the value of the true ball on; progression, foul values, Return interaction, undo and replay are deterministic.
- **Evidence:** `free-ball.ts`; `free-ball-commands.ts`; `free-ball.test.ts`.
- **Assessment:** `SPECIALIZES_OFFICIAL_RULE` through explicit operator facts.

## Declining a Free Ball

- **Salloon behavior:** decline is presentation-local and creates no sporting event. Reload before the next stroke may offer the choice again.
- **Evidence:** `docs/SNOOKER_IMPLEMENTATION_HANDOFF.md`, Phase L.
- **Assessment:** `SIMPLIFIES_OFFICIAL_RULE`; the sporting consequence is unchanged, but the decline is not audited.

## Respotted Black

- **Salloon behavior:** a tied final Black enters a pending state. The starter is an explicit human draw/choice result, with cue ball logically in the D. A pot or foul on the Black ends the frame; a legal no-pot transfers the turn and leaves Black on. Miss, Return and Free Ball continuations are unavailable after the decisive action.
- **Evidence:** `respotted-black-commands.ts`; `respotted-black.test.ts`.
- **Assessment:** `MATCHES_OFFICIAL_RULE` for modeled actions.

## Concession

- **Salloon behavior:** either match player may concede any unfinished Snooker frame, including the score leader and during final Colours or pending respotted Black. The opponent receives the frame with no invented score or break points.
- **Evidence:** `match-commands.ts#concedeSnookerFrame`; `respotted-black.test.ts` concession tests.
- **Assessment:** `DIFFERS_FROM_OFFICIAL_RULE`; this is an intentional Salloon product rule.

## Automatic unreachable-lead completion

- **Salloon behavior:** Snooker has no automatic unreachable-lead completion. Table progression, accepted concession, repeated-Miss award or decisive final/respotted Black action completes a frame.
- **Evidence:** `docs/SNOOKER_IMPLEMENTATION_HANDOFF.md`, Phase C/H; projection and match command tests.
- **Assessment:** `DIFFERS_FROM_OFFICIAL_RULE` / `REQUIRES_REVIEW` for future product handling of official Black-only claims/awards.

## Touching Ball and physical geometry

- **Salloon behavior:** general Touching Ball gameplay and table geometry are not modeled. A narrow explicit referee fact preserves the repeated-Miss eligibility seam, and geometry-dependent decisions remain human inputs.
- **Evidence:** `traditional-miss-policy.ts`; `traditional-miss.test.ts`; `docs/SNOOKER_IMPLEMENTATION_HANDOFF.md`.
- **Assessment:** `REQUIRES_REVIEW`; JumaRAG must not imply that Salloon calculated geometry.


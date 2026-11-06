# AIcode task: normalized process CPU display

## Status

Planning only. No implementation, AIcode execution, or test results are claimed by this document.

## Branch scope

Start from copilot/implement-browser-telemetry at 80591bacedab71eb6713af89a9aa662eee53dab5. Keep this work independent of PR #23. Target the PR #22 branch, not main. Do not merge either existing PR.

## Acceptance criteria

- Inspect existing CPU normalization, REST fields, browser rendering, and text panel before editing. Reuse existing normalized readings rather than duplicate or resample them.
- Display current and recent-average process CPU relative to available logical CPUs, and the CPU count used.
- Preserve legacy REST fields and their semantics.
- Format finite percentages to two decimal places with a percent suffix; preserve zero and do not clamp.
- Handle current and average independently. For missing values, show Collecting only in collecting state, otherwise Unavailable. Never coerce null to zero.
- Mark retained values as stale when the stale flag or state requires it, including partial readings. Handle invalid or unavailable CPU counts explicitly.
- Preserve textContent, responsive layout, visibility, polling, CSP, and authentication. Avoid unrelated changes.
- Update the text panel consistently if used, using the captured normalized reading.
- Document division by the last sampled available logical CPU count, the non-capacity-weighted recent average when that count changes, and that this is CPU-time rather than frequency-weighted normalization.
- Extend existing tests for available, zero, missing, collecting, unavailable, partial, stale, invalid-count, and non-clamping cases.

## Validation

Run relevant JavaScript tests, python3 test/telemetry-rest-test.py, and bash test/run.sh where available. Compile Android if an SDK is available. Report exact commands, results, and environment blockers; do not claim unexecuted checks passed.

Implementation is intended for the local supervised AIcode workflow. Publication and merging require human review.

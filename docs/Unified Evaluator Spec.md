# Unified Evaluator Spec

This is a living spec for how TVRenamer turns an extracted show name (from a filename) into a resolved show, and under what conditions the UI must prompt the user via **Select Shows**.

It documents **current behavior** and the **implemented decision ordering** for the unified evaluator so runtime behavior and Preferences validation remain in sync.

Note: further tie-breaker expansion is intentionally deferred; see `docs/TODO.md` for any remaining/next heuristic work items.

Source of truth:
- `org.tvrenamer.model.ShowSelectionEvaluator` (pure evaluator used by both runtime selection and Matching-tab validation)

---

## Glossary

- **Extracted show name** (`foundName`)
  - The substring extracted from a filename that is believed to be the show name.
  - Example: from `The.Quiet.Ones.s01e03.mkv`, this is typically `The.Quiet.Ones`.

- **Override**
  - A user preference mapping from extracted show name → replacement text.
  - Used to improve provider queries and downstream naming behavior.

- **Query string** (`queryString`)
  - A normalized form of a show name used as the provider search key and as an internal cache key.
  - Currently computed by `StringUtils.makeQueryString(...)` (lowercase + punctuation normalization).
  - Example: `The.Quiet.Ones` → `the quiet ones`.

- **Provider candidates / show options**
  - A list of `ShowOption` values returned by the provider’s search API for a query string.
  - Candidates include at least: provider id and series name; may include year and aliases.

- **Disambiguation (pin)**
  - A user preference mapping from query string → provider series id.
  - Used to force selection when a query returns multiple plausible candidates.

- **Select Shows dialog**
  - UI used when a query returns multiple candidates and selection is still ambiguous after heuristics.

---

## End-to-end pipeline (current)

### 1) Filename parsing extracts `foundName`

`FilenameParser.parseFilename(...)` finds a regex match and extracts the show-name group.

Normalization at this stage is minimal:
- `StringUtils.trimFoundShow(...)` removes leading/trailing separators: spaces, `_`, `.`, `-`
- It does **not** replace internal separators like dots.
  - So `The.Quiet.Ones` remains `The.Quiet.Ones` at this stage.

The `FileEpisode` stores:
- `episode.setFilenameShow(foundName)` (original extracted name, mostly unchanged)

### 2) Overrides are applied for lookup initiation

Before a provider lookup is started:
- `UserPreferences.resolveShowName(foundName)` may return a replacement string
- `ShowName.mapShowName(overriddenName)` is used for lookup/caching

Important nuance:
- The file row still “displays” / tracks the extracted name (`foundName`)
- But the lookup key for a ShowName instance is the overridden name (if an override exists)

### 3) Query string normalization (provider key + cache key)

`ShowName` internally maps its `foundName` to a `QueryString` object:

- `QueryString.lookupQueryString(foundName)` computes:
  - `StringUtils.makeQueryString(foundName)`
  - which is effectively `toLower(replacePunctuation(foundName))`

This is the key that:
- is sent to the provider search endpoint
- is used as the disambiguation key in preferences
- is used as the in-memory cache key for `QueryString`

Example:
- `The.Quiet.Ones` → `the quiet ones`
- `The Quiet Ones` → `the quiet ones`

So dot-separated filenames already query correctly, even when the extracted show name contains dots.

### 4) Provider candidates are fetched

`ShowStore.downloadShow(showName)` calls the provider to populate candidates:
- `TheTVDBProvider.getShowOptions(showName)`
- Candidates are stored in `showName.getShowOptions()`

### 5) Selection decision tree (current)

After candidates are available, selection proceeds in this order:

#### 5.1) Use stored disambiguation by query string (highest priority)

If user preferences contain a pinned mapping:
- key: `queryString` (normalized)
- value: preferred provider id

Then:
- If that provider id exists in the current candidate list, select it.
- If it does not exist in current results, the provider fetches that series directly by id
  and adds it to the candidates (`EpisodeDataProvider.getShowOptions(showName, pinnedId)`),
  so it is then selected. A provider's name search can miss a series that exists; the pin
  already says which series is meant. Only an all-digit id is looked up.
- If the provider does not know that id either, the plain search results stand and
  selection falls back to auto-selection.

This avoids prompting when the user has already disambiguated that query.

#### 5.2) Auto-select by exact extracted-name match or alias match (case-insensitive)

If there is no stored disambiguation, attempt a best-effort match that still avoids prompting:

- Compare each candidate’s `SeriesName` to:
  - the extracted name as-is (case-insensitive)
  - a punctuation-normalized form of the extracted name via `StringUtils.replacePunctuation(...)`

- If no SeriesName match, compare aliases similarly:
  - alias equals extracted name (case-insensitive)
  - alias equals punctuation-normalized extracted name (case-insensitive)

This step is intentionally conservative: it only selects when a candidate matches the extracted name (in humanized form), not by fuzzy scoring.

Rationale:
- Avoid unnecessary prompts for dot/underscore-separated filenames, e.g.
  - `The.Quiet.Ones.s01e03.mkv`
  - candidates: `The Quiet Ones`, `The Quiet Ones (IN)`, `The Quiet Ones (CN)`
  - The first should be selected without prompting because it matches the humanized extracted name.

#### 5.3) If still ambiguous and there are multiple candidates: prompt

If:
- no pinned disambiguation
- no exact/alias match from 5.2
- and `options.size() > 1`

Then:
- Add a pending disambiguation entry keyed by query string
- Mark the lookup as blocked on user selection
- UI will surface this as “Select Show…” (batch dialog)

#### 5.4) Otherwise use legacy selection behavior (`ShowName.selectShowOption()`)

If:
- there is exactly one candidate, select it
- if there are zero candidates, treat as “not found”
- in fallback cases, `selectShowOption()` may select the first option if there was no exact `foundName` match

Note: the prompting behavior for true ambiguity is governed by 5.3; we avoid “silently pick first result” when multiple candidates exist and no strong heuristic match exists.

---

## Unified selection evaluator (implemented; source of truth)

To avoid duplicated logic and “drift” between runtime behavior and Preferences validation, selection rules live in a single, pure evaluator function:

- `org.tvrenamer.model.ShowSelectionEvaluator.evaluate(extractedName, options, pinnedId)`

### Goals

- Runtime show resolution and Matching-tab validation must use the same decision logic.
- No UI side effects in the evaluator (no dialog, no queuing).
- Deterministic and explainable: return a decision + a human-readable reason string.

### Input semantics

The evaluator accepts:
- `extractedName` (String): the name to match against candidate SeriesName/Aliases.
  - Runtime: this is the extracted show name (user-facing), e.g. `The.Quiet.Ones`
  - Override validation: this is the override **replacement text**
- `options` (`List<ShowOption>`): provider candidates (may be empty)
- `pinnedId` (String|null): the user’s pinned provider id for this query string, if any

It returns a decision:
- `RESOLVED` (chosen candidate + reason)
- `AMBIGUOUS` (reason; runtime would prompt)
- `NOT_FOUND` (reason)

### How it is used

- **Runtime (ShowStore):**
  - Compute `pinnedId` using the query string (`prefs.resolveDisambiguatedSeriesId(queryString)`).
  - Fetch provider candidates, passing `pinnedId` so a pinned series the search missed is
    fetched by id.
  - Call the evaluator.
  - If `AMBIGUOUS`, then queue a pending disambiguation entry and require Select Shows.
  - If `RESOLVED`, proceed without prompting.

- **Preferences Matching tab validation:**
  - Compute `pinnedId` using the same query-string key that runtime would use.
  - Fetch provider candidates with that `pinnedId`, as runtime does (best-effort; provider
    may be unavailable).
  - Call the evaluator and treat:
    - `RESOLVED` as “valid / would not prompt”
    - `AMBIGUOUS` as “invalid / would prompt”
    - `NOT_FOUND` as “invalid / no matches”

#### Override validation semantics (pragmatic)

For an Override row (`extracted show` → `replacementText`), validation treats:
- `replacementText` as the evaluator’s `extractedName` input (because that is the string we intend to resolve/search as).
- The pinned-id lookup key as `StringUtils.makeQueryString(replacementText)`.

This answers: “Given current disambiguations, is this override likely to resolve without prompting?”

### Decision ordering (implemented)

The evaluator is intentionally conservative and explainable. It uses the following ordering:

1. **No matches**
   - If `options` is empty → `NOT_FOUND` (“No matches”).

2. **Pinned ID wins (if present and in results)**
   - If `pinnedId` matches a candidate id in `options` → `RESOLVED` (“Resolved via pinned ID”).

3. **Exact SeriesName match wins (even if other candidates exist)**
   - If any candidate SeriesName equals `extractedName` (case-insensitive) → `RESOLVED` (“Resolves via exact name match”).
   - Additionally, compare against a punctuation-normalized form of `extractedName` (via `StringUtils.replacePunctuation`) to handle common download separators like `.`, `_`, `-`.

4. **Exact Alias match wins**
   - If any candidate alias equals `extractedName` (raw or punctuation-normalized; case-insensitive) → `RESOLVED` (“Resolves via exact alias match”).

5. **Tie-breakers (deterministic; applied only after exact checks)**
   - **TB1: Prefer base title over parenthetical variants (only when base exists)**
     - If a base-title candidate exists (exactly equal to normalized extracted name) and other candidates are `Base Title (something)` → choose base title (“Preferred base title over parenthetical variants”).
     - If candidates are only parenthetical variants (e.g., `The Bullpen (US)` vs `The Bullpen (UK)` with no base title) → remain ambiguous (prompt).
   - **TB2/TB6: Prefer exact token match over extra tokens**
     - Canonicalize extracted and candidate names using punctuation replacement + lowercase + collapsed spaces.
     - If exactly one candidate’s canonical tokens equal the extracted canonical tokens → choose it (“Preferred exact token match over extra tokens”).
   - **TB3: FirstAiredYear match with ±1 tolerance when extracted contains a year token**
     - If extracted contains a year token (e.g., `(... 2023 ...)`) and no exact SeriesName match existed,
       then if exactly one candidate has `firstAiredYear` within ±1 of that year → choose it (“Resolved via FirstAiredYear (±1) match”).

6. **Single candidate**
   - If `options.size() == 1` → `RESOLVED` (“Resolves uniquely”).

7. **Otherwise**
   - `AMBIGUOUS` (“Still ambiguous (would prompt)”).

Non-goals:
- No fuzzy matching (Levenshtein, scoring) at this stage.
- No “prefer base title” when the extracted name already contains a parenthetical suffix (that should be handled by exact match, which wins by specificity).

---

## Behavioral invariants / design constraints

These constraints are intentional:

1. **Do not mutate the stored/displayed extracted show name globally.**
   - The extracted string is user-facing and should reflect the filename.
   - Heuristics can use derived normalized forms without altering `foundName`.

2. **Query string normalization should remain centralized.**
   - Provider querying and disambiguation keys rely on `StringUtils.makeQueryString(...)`.
   - Avoid introducing alternative query normalization paths in multiple locations.

3. **Disambiguation keys should be stable.**
   - Disambiguations are stored by query string.
   - Any changes to `makeQueryString` semantics are migration-sensitive and should be treated carefully.

4. **Auto-selection should be conservative.**
   - Prefer “exact after normalization” matches (SeriesName/alias) over fuzzy scoring.
   - If multiple candidates remain plausible, prompt.

---

## Known gaps / planned improvements

This section lists improvements that can be layered in without destabilizing the pipeline.

Status: further tie-breaker work is deferred; track any additional heuristics and test coverage work in `docs/TODO.md` before implementing changes here.

1. **Additional tie-breakers when multiple candidates match equally (DEFERRED)**
   - Any new tie-breakers must be deterministic and explainable.
   - Record proposed heuristics (with examples) in `docs/TODO.md` before implementation, to avoid unexpected auto-selections.

2. **Fuzzy matching (deferred / optional)**
   - If introduced, gate it carefully to avoid surprising auto-selections.
   - Consider only as a tie-breaker after exact/alias matches fail.

3. **Better handling of parenthetical suffixes**
   - e.g., `(US)`, `(UK)`, `(IN)`, `(CN)` patterns
   - Planned tie-breaker: if the extracted show name (as-is or punctuation-normalized) does not include a parenthetical suffix,
     prefer the candidate whose series name exactly equals the normalized extracted show name (i.e., the “base title”),
     ahead of candidates that only match via a suffixed form like `Title (IN)`.

4. **Spec-driven tests (no live network calls)**
   - Add unit tests that lock in the intended “dot-separated filename avoids prompt” behavior.
   - Tests must not depend on live TVDB/provider availability (CI environments may block outbound calls).
   - Prefer fixture-based candidate injection or provider mocking so we can test selection deterministically.

---

## Example scenarios (current intent)

### Scenario A: Dot-separated filename with clear base match
Input file: `The.Quiet.Ones.s01e03.mkv`  
Extracted: `The.Quiet.Ones`  
Normalized compare: `The Quiet Ones`  

Candidates:
- `The Quiet Ones`
- `The Quiet Ones (IN)`
- `The Quiet Ones (CN)`

Expected:
- auto-select `The Quiet Ones`
- no Select Shows prompt

### Scenario B: Genuine ambiguity without a strong match
Extracted: `The Bullpen`  
Candidates:
- `The Bullpen`
- `The Bullpen (US)` (or similar)
- `The Bullpen (UK)` (or similar)

Expected:
- prompt unless user pinned a disambiguation for that query string

### Scenario C: User pinned disambiguation
Query string: `the bullpen`  
Pinned id: `<provider-id-for-us>`  

Expected:
- select pinned id immediately if present in provider results
- no prompt

---

## Change log

- Initial spec: documents pipeline and current selection decision tree.
- Added: unified selection evaluator plan (shared by runtime + Matching validation) and override validation semantics.
- Implemented: unified selection evaluator (`org.tvrenamer.model.ShowSelectionEvaluator`) is now the source of truth for runtime selection and Matching-tab override validation.
- Implemented: deterministic tie-breakers (base-title vs parenthetical variants, strict token match, FirstAiredYear ±1 when a year token is present).

```
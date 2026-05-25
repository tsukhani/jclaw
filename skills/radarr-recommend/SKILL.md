---
name: radarr-recommend
icon: 📺
description: Get personalized movie recommendations based on user's preferences. Checks library to avoid duplicates.
version: 1.0.3
author: main
tools: [exec, filesystem]
commands: []
---
# Movie Recommendations

Personalized movie recommendations that learn each user's taste.

## Credentials

```bash
# Agent exec runs with CWD = workspace root, so the credentials file
# (shared with the radarr skill) sits at a known workspace-relative
# path. Do NOT use $BASH_SOURCE — exec is `bash -c "…"`, where
# $BASH_SOURCE[0] is empty and would mis-resolve SCRIPT_DIR.
CREDS="skills/radarr/credentials/radarr.json"
RADARR_URL=$(jq -r '.radarr.url' "$CREDS")
RADARR_API_KEY=$(jq -r '.radarr.apiKey' "$CREDS")
# NOTE: radarr.json may also include torrents/synology paths; this skill only needs Radarr URL + API key.
```

---

## Prerequisites: TMDb Import Lists

The skill relies on TMDb import lists configured in Radarr (with `enableAuto: false` so they don't auto-add movies). These provide the trending/popular movie data via `GET /api/v3/importlist/movie`.

**Required import lists (already configured):**

| ID | Name | TMDb List Type | Description |
|----|------|---------------|-------------|
| 1 | TMDb Popular | Popular (2) | Currently popular movies on TMDb |
| 2 | TMDb Upcoming | Upcoming (4) | Movies with upcoming releases |
| 3 | TMDb In Theaters | Theaters (1) | Currently in cinemas |
| 4 | TMDb Top Rated | Top (3) | TMDb's highest rated movies |

All lists are set to `enableAuto: false` — they only provide data for recommendations, never auto-add movies.

**If lists need to be recreated:**
```bash
curl -s -X POST "$RADARR_URL/api/v3/importlist" \
  -H "X-Api-Key: $RADARR_API_KEY" -H "Content-Type: application/json" \
  -d '{
    "name": "TMDb Popular",
    "enabled": true, "enableAuto": false,
    "monitor": "movieOnly", "rootFolderPath": "[STORAGE_PATH]",
    "qualityProfileId": 1, "searchOnAdd": false, "minimumAvailability": "tba",
    "listType": "tmdb", "implementation": "TMDbPopularImport", "configContract": "TMDbPopularSettings",
    "fields": [
      {"name": "tMDbListType", "value": 2},
      {"name": "filterCriteria.minVoteAverage", "value": "5"},
      {"name": "filterCriteria.minVotes", "value": "1"},
      {"name": "filterCriteria.languageCode", "value": 2}
    ],
    "tags": []
  }'
```
Change `tMDbListType` value: 1=Theaters, 2=Popular, 3=Top, 4=Upcoming.

---

## User Preferences Storage

**Primary location:** USER.md in the current workspace

Look for a `## Movie Preferences` section in USER.md. This keeps preferences co-located with other user info and works naturally in multi-agent setups.

### Expected USER.md Format

```markdown
## Movie Preferences

- **Favorite genres:** Sci-fi, thriller, horror, drama
- **Avoid genres:** Rom-com, sports, kids
- **Minimum rating:** 6.5 IMDb
- **Preferred eras:** 2020s, 2010s
- **Favorite directors:** [PERSONAL_NAME_1], [PERSONAL_NAME_2]
- **Notes:** Loves AI themes, prefers atmospheric horror over slasher
```

---

## Workflow

### Step 1: Check USER.md for Preferences

```bash
# Read USER.md and look for "## Movie Preferences" section
grep -A 10 "^## Movie Preferences" USER.md 2>/dev/null
```

**Check if preferences are actually filled in:**
- Look for the `## Movie Preferences` section
- Check that `Favorite genres:` has actual content (not empty, not `*(not set)*`)
- If genres are filled → preferences exist, proceed to Step 2
- If genres are empty/placeholder → run onboarding (Step 1b)

**Example of FILLED preferences (ready to use):**
```markdown
## Movie Preferences

- **Favorite genres:** Action, sci-fi, thriller
- **Avoid genres:** Romance
- **Minimum rating:** 7 IMDb
```

**Example of UNFILLED preferences (needs onboarding):**
```markdown
## Movie Preferences

- **Favorite genres:** *(not set)*
- **Avoid genres:** *(not set)*
```

**If preferences are filled:** Use them for recommendations (skip to Step 2)

**If preferences are missing or unfilled:** Start the onboarding flow (Step 1b)

### Step 1b: Onboarding (No Preferences Found)

**📢 Ask:**
```
🎬 Hey! I don't know your movie taste yet. How would you like me to learn?

1️⃣ **Tell me** — You describe your preferences (genres, ratings, etc.)
2️⃣ **Show me** — I'll show you movies, you pick what looks good

Which works better for you?
```

---

#### Option 1: Explicit Preferences

If user chooses "tell me" / "1":

Ask these questions:
- "**What genres do you love?** (e.g., sci-fi, horror, action, comedy, thriller)"
- "**Any genres to avoid?**"
- "**Minimum IMDb rating?** (e.g., 6.5, 7.0, or 'any')"
- "**Favorite directors or actors?** (or 'skip')"
- "**Anything else?** (e.g., 'love anime', 'no subtitles', 'classic films')"

Save preferences to USER.md after (see Step 1c).

---

#### Option 2: Learning from Choices

If user chooses "show me" / "2":

**Step A: Gather Sample Movies**

Pull a diverse mix from:
1. **Existing library** (movies already downloaded):
```bash
curl -s "$RADARR_URL/api/v3/movie" \
  -H "X-Api-Key: $RADARR_API_KEY" | \
  jq '[.[] | select(.hasFile == true) | {title, year, genres}] | group_by(.genres[0]) | map(.[0]) | .[0:10]'
```

2. **Radarr import lists** (trending/popular from TMDb):
```bash
# Fetch diverse sample from TMDb import lists, one per genre
curl -s "$RADARR_URL/api/v3/importlist/movie" \
  -H "X-Api-Key: $RADARR_API_KEY" | \
  jq '[.[] | select(.isExisting == false) | {title, year, genres, imdb: .ratings.imdb.value}] | group_by(.genres[0]) | map(sort_by(-.imdb)[0]) | .[0:10]'
```

**Step B: Present Diverse Selection**

**📢 Show:**
```
🎬 **Pick the movies that interest you** (just the numbers):

1️⃣ **Dune: Part Two** (2024) — Sci-fi epic
2️⃣ **Longlegs** (2024) — Psychological horror
3️⃣ **The Holdovers** (2023) — Character drama
4️⃣ **John Wick 4** (2023) — Action
5️⃣ **Oppenheimer** (2023) — Historical drama
6️⃣ **Spider-Verse** (2023) — Animation
7️⃣ **Barbie** (2023) — Comedy
8️⃣ **Past Lives** (2023) — Romance/drama
9️⃣ **Godzilla x Kong** (2024) — Monster action
🔟 **Poor Things** (2023) — Surreal drama

Pick as many as you like! (e.g., "1, 2, 5, 10")
```

**Step C: Analyze Choices**

Based on their picks, infer preferences:
- Picked 1, 5, 10 → likes cerebral/artistic films
- Picked 2, 4, 9 → likes action/horror
- Avoided 7, 8 → probably not into rom-com

**📢 Confirm:**
```
🧠 Based on your picks, I'm seeing:

✅ **You like:** Sci-fi, thriller, drama, visually ambitious films
❌ **Not so much:** Light comedy, pure romance

Does that sound right? Anything to add or correct?
```

**Step D: Save Inferred Preferences**

Convert their choices into the standard preference schema and save to USER.md (see Step 1c).

---

### Step 1c: Update USER.md with Preferences

After gathering preferences (via explicit questions, learning from choices, or hybrid), append a `## Movie Preferences` section to USER.md:

```markdown
## Movie Preferences

- **Favorite genres:** {genres from onboarding}
- **Avoid genres:** {avoided genres, or "None specified"}
- **Minimum rating:** {rating} IMDb
- **Preferred eras:** {eras, or "Any"}
- **Favorite directors:** {directors, or "None specified"}
- **Notes:** {any additional notes from conversation}
```

**Use the Edit tool** to append this section before the final `---` line in USER.md.

**Confirm to user:**
```
✅ Saved your movie preferences to your profile! I'll use these for future recommendations.
```

---

#### Hybrid Approach

User can also just start chatting naturally:
- "I like horror" → Note it
- "No romance please" → Note it
- Build preferences gradually through conversation

### Step 2: Fetch Trending Movies from Radarr

Use Radarr's import list API to get trending/popular movies from TMDb. This endpoint returns movies from all configured TMDb import lists (Popular, Upcoming, In Theaters, Top Rated) and automatically flags which ones are already in the library.

```bash
# Fetch all trending movies from configured import lists
TRENDING=$(curl -s "$RADARR_URL/api/v3/importlist/movie" -H "X-Api-Key: $RADARR_API_KEY")

# Total available
echo "$TRENDING" | jq 'length'
```

**The response includes rich data per movie:**
- `title`, `year`, `overview`, `runtime`, `genres[]`, `certification`
- `ratings.imdb.value`, `ratings.tmdb.value`, `ratings.trakt.value`
- `isExisting` — `true` if already in library (auto-detected by Radarr)
- `isExcluded` — `true` if user has excluded this movie
- `lists[]` — which import list IDs the movie appears on:
  - **1** = TMDb Popular
  - **2** = TMDb Upcoming
  - **3** = TMDb In Theaters
  - **4** = TMDb Top Rated

### Step 3: Filter & Rank

**No web search needed** — all data comes from Radarr's TMDb import lists.

**Two-tier approach:** Split results into preferred genre matches (top) and other good movies (below).

#### 3a. Base filtering (apply to all movies)

```bash
# Remove library movies, excluded movies, and avoided genres
FILTERED=$(echo "$TRENDING" | jq '
  [.[] |
    select(.isExisting == false) |
    select(.isExcluded == false) |
    select((.genres | map(ascii_downcase) | any(. == "AVOIDED_GENRE_1" or . == "AVOIDED_GENRE_2")) | not) |
    select(.ratings.imdb.value >= MIN_RATING or .ratings.imdb.value == 0) |
    {title, year, overview, genres, runtime, certification,
     imdb: .ratings.imdb.value,
     tmdb: .ratings.tmdb.value,
     trakt: .ratings.trakt.value,
     lists}
  ]')
```

#### 3b. Split into preferred vs other

Read the user's favorite genres from USER.md, then split:

```bash
# Tier 1: Movies matching user's preferred genres (sorted by IMDb rating)
PREFERRED=$(echo "$FILTERED" | jq '
  [.[] |
    select(.genres | map(ascii_downcase) | any(
      . == "science fiction" or . == "fantasy" or . == "action"
    ))
  ] | sort_by(-.imdb)')

# Tier 2: Everything else that passed base filters (also sorted by IMDb)
OTHER=$(echo "$FILTERED" | jq '
  [.[] |
    select((.genres | map(ascii_downcase) | any(
      . == "science fiction" or . == "fantasy" or . == "action"
    )) | not)
  ] | sort_by(-.imdb)')
```

**Present Tier 1 first, then Tier 2** — so the user always sees their preferred genres at the top.

#### Ranking within each tier

- Primary: IMDb rating (descending)
- Secondary: Number of lists appeared on (movie on 2+ lists = extra visibility)
- Tertiary: Trakt rating as tiebreaker

#### Summary of filtering rules

| Rule | Action |
|------|--------|
| `isExisting == true` | ❌ Exclude (already in library) |
| `isExcluded == true` | ❌ Exclude |
| Below min IMDb rating | ❌ Exclude (but keep unrated/new movies with 0 rating) |
| Avoided genres | ❌ Exclude entirely |
| Preferred genres | ⬆️ Tier 1 (shown first) |
| Other genres | ⬇️ Tier 2 (shown after preferred) |
| Multiple list appearances | ⬆️ Boost within tier |

### Step 4: Present Recommendations

Present in two sections — preferred genres first, then the rest.

**Format:**
```
🎬 **Picks for {name}:**

⭐ **Your Genres** (Sci-Fi, Fantasy, Action)

**1️⃣ [Movie Title] ([Year])** ⭐ IMDb [X.X]
[overview from Radarr] • [runtime] min • [certification]
📊 TMDb: [X.X] • Trakt: [X.X]
🏷️ [genres] • {list labels: 🔥 Popular, 🎬 In Theaters, ⏳ Upcoming, 🏆 Top Rated}

**2️⃣ [Movie Title] ([Year])** ⭐ IMDb [X.X]
...

🍿 **Also Trending**

**3️⃣ [Movie Title] ([Year])** ⭐ IMDb [X.X]
[overview] • [runtime] min • [certification]
📊 TMDb: [X.X] • Trakt: [X.X]
🏷️ [genres] • {list labels}

...

---

Which ones interest you? Just say the number(s) to download! 🍿
```

**Notes:**
- Show 3-5 preferred genre movies in the top section
- Show 2-3 "Also Trending" movies below (different genres the user might enjoy)
- Numbering is continuous across both sections (1-8)
- If no preferred genre matches exist, skip the top section and show all as trending

**List label mapping:**
- List 1 → 🔥 Popular
- List 2 → ⏳ Upcoming
- List 3 → 🎬 In Theaters
- List 4 → 🏆 Top Rated

### Step 5: Confirm Before Download

When user picks movie(s):

**📢 Ask:**
```
🎬 Great choice! **[Movie Title] ([Year])**

Want me to download it now?
```

**Wait for user to confirm** (yes/sure/go ahead/download it).

- If **yes** → Hand off to `radarr` skill for download
- If **no** → "No problem! Let me know when you're ready."
- If **picks another** → Add to list, ask again

### Step 6: Hand Off to Downloader

Only after user confirms:

1. **Hand off to `radarr` skill** (movie downloader)
2. Use the standard download workflow (Interactive Search → user picks release → download)

### Step 7: Update History

After download completes (or if user rejects):

```json
{
  "history": [
    {"title": "Movie Name", "liked": true, "date": "2026-01-30"}
  ]
}
```

**Ask occasionally:** "Did you enjoy [Movie]?" to refine recommendations.

---

## Learning Over Time

### Positive Signals (increase weight)
- User downloads a recommended movie
- User says they liked a movie
- User asks for more of a genre/director

### Negative Signals (decrease weight)
- User rejects a recommendation
- User says they didn't like a movie
- User explicitly says "no more [genre]"

### Update Preferences

When learning new info, update the `## Movie Preferences` section in USER.md:

1. Read the current section
2. Modify the relevant line (e.g., add a genre to favorites)
3. Use the Edit tool to replace the old section with the updated one

**Example:** User says "I also like anime" → Add "anime" to Favorite genres line.

---

## Trigger Phrases

- "Recommend a movie"
- "What should I watch?"
- "Movie suggestions"
- "What's good?"
- "Find me something to watch"
- "Any good [genre] movies?"

---

## Example Conversations

**New user (no preferences in USER.md):**
```
User: "recommend a movie"
Agent: [checks USER.md, no Movie Preferences section found]
Agent: "🎬 Hey! I don't have your movie preferences yet. Want to (1) tell me your preferences, or (2) pick from movies so I can learn?"
User: "1"
Agent: "What genres do you love?"
User: "horror and action"
Agent: "Nice! Any genres to avoid?"
User: "no romance"
Agent: "Minimum IMDb rating?"
User: "7"
Agent: "Perfect! ✅ Saved your movie preferences to your profile!"
[updates USER.md with ## Movie Preferences section]
Agent: "🎬 Now let me find some picks for you!"
[recommends horror/action movies rated 7+]
```

**New user (learning path):**
```
User: "suggest something to watch"
Agent: [checks USER.md, no preferences found]
Agent: "🎬 I don't have your preferences yet. Want to (1) tell me, or (2) pick from movies so I can learn?"
User: "2"
Agent: "🎬 Pick the movies that interest you:
        1️⃣ Dune: Part Two — Sci-fi epic
        2️⃣ Longlegs — Psychological horror
        ... [diverse list]"
User: "1, 2, 5"
Agent: "🧠 Based on your picks: You like cerebral sci-fi, horror, drama. Sound right?"
User: "yeah, but I also like action"
Agent: "Got it! ✅ Saved your preferences to your profile!"
[updates USER.md with inferred + stated preferences]
Agent: "Let me find recommendations..."
```

**Returning user (preferences exist in USER.md):**
```
User: "what should I watch?"
Agent: [checks USER.md, finds ## Movie Preferences section]
Agent: [fetches /api/v3/importlist/movie from Radarr]
Agent: [filters: isExisting=false, excludes horror/romance, min 7.0 IMDb]
Agent: [presents 5-8 matches sorted by IMDb rating]
User: "2"
Agent: "🎬 Great choice! **Movie Title (2025)** — Want me to download it now?"
User: "yes"
Agent: "⬇️ On it! Let me find the best release..."
[hands off to radarr skill]
```

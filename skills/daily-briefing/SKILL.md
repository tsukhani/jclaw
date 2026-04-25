---
name: daily-briefing
description: Generate a daily briefing with tech news, business news, world news, local weather, and upcoming events based on user's city, including the generation time
version: 1.1.3
tools: [datetime, web_search, web_fetch]
---
# Daily Briefing

When asked to generate a daily briefing, follow these steps to produce a structured, well-formatted report.

## Steps

1. **Determine the User's City**
   - First, check in USER.md to see if the user's location is already specified (look for "Location:" field)
   - If found in USER.md, use that location as the default city
   - If not found in USER.md or if the user explicitly asks for a different city in their request, prompt the user: "Which city would you like the briefing for?"
   - Wait for the user's response before proceeding only if no city can be determined from context

2. **Get Current Time and Date**
   - Call the `datetime` tool with action `now` to get the accurate current date, time, and timezone.
   - Use the returned values for the briefing header and "Generated" timestamp. Do NOT guess or infer the time from any other source.

3. **Fetch the Weather for the Specified City**
   - Use `web_search` with a query like: `"{city} weather today"` (e.g., "New York weather today")
   - Extract current temperature, conditions (sunny, cloudy, rain, etc.), humidity if available, and any notable forecasts for the day.

4. **Determine the Country**
   - Infer the country from the city name provided by the user. For example:
     - "New York", "Los Angeles" → United States
     - "London", "Manchester" → United Kingdom
     - "Tokyo", "Osaka" → Japan
     - "Sydney", "Melbourne" → Australia
     - If uncertain, use `web_search` to confirm: `"{city} country"`

5. **Fetch Upcoming Events for the City**
   - Use `web_search` with a query like: `"upcoming events {city} {current month} | {city} events calendar"` (e.g., "upcoming events New York April")
   - Extract major festivals, concerts, exhibitions, sports events, conferences, and other notable happenings in the city
   - Aim for 4–5 upcoming events with dates and brief descriptions

6. **Fetch Local News for the Inferred Country**
   - Use `web_search` with a query like: `"top news {country} today"` (e.g., "top news United States today")
   - Extract the top 4–5 headlines with a brief one-line summary for each.

7. **Fetch Tech News**
   - Use `web_search` with a query like: `"top tech news today"`
   - Extract the top 4–5 headlines with a brief one-line summary for each.

8. **Fetch Business News**
   - Use `web_search` with a query like: `"top business news today"`
   - Extract the top 4–5 headlines with a brief one-line summary for each.

9. **Fetch World News**
   - Use `web_search` with a query like: `"top world news today"`
   - Extract the top 4–5 headlines with a brief one-line summary for each.

10. **Return the Briefing Directly**
   - Format the output using the structure below.
   - **Do NOT write a markdown file.** Simply return the formatted briefing as the response to the user.
   - Use clear section headers and bullet points.
   - Keep each bullet concise (1–2 sentences max).
   - Include the date (without time) in the header, and include both the date and time in the "Generated" timestamp line with timezone.

## Output Format

```
# 📋 Daily Briefing — Monday, April 13, 2026

*Generated: Monday, April 13, 2026 at 12:39 AM (GMT+8, Asia/Kuala_Lumpur)*

---

## 🌤️ Weather — {City}
- **Condition:** [e.g., Partly Cloudy]
- **Temperature:** [e.g., 28°C]
- **Humidity:** [e.g., 80%]
- **Forecast:** [e.g., Expect afternoon showers, clearing by evening]

---

## 📅 Upcoming Events — {City}
- **[Event Name] — [Date]:** [Brief description of the event]
- **[Event Name] — [Date]:** [Brief description of the event]
- **[Event Name] — [Date]:** [Brief description of the event]
- **[Event Name] — [Date]:** [Brief description of the event]

---

## 📰 Local News — {Country}
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]

---

## 💻 Tech News
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]

---

## 📈 Business News
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]

---

## 🌍 World News
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]
- **[Headline]:** [One-line summary]
```

## Tools to Use
- `datetime` — call with action `now` to get the accurate current date, time, and timezone for the briefing header
- `web_search` — for all news, weather, events queries, and for confirming city-country mapping if needed
- `web_fetch` — optionally fetch full articles if more detail is needed

## Guidelines
- Check USER.md for the user's location first, only ask if not found or if the user explicitly requests a different city
- Always include today's date **and current time** in the header, plus a "Generated" timestamp line with timezone
- Prioritize recent, credible sources
- If a section returns no results, note it gracefully (e.g., "No major updates found.")
- Keep the tone informative and neutral
- If the user provides a city that could be in multiple countries (e.g., "Springfield"), ask for clarification
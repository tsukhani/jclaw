---
name: restaurant-recommender
description: Search the web for highest-rated restaurants in a specified city and provide findings directly in chat with names, ratings, reviews, and contact information.
version: 1.0.2
tools: [web_search, web_fetch]
---
# Restaurant Recommender

When asked to find top-rated restaurants:

## 1. Identify the Target City

**Check if a city was specified** in the user's request.

- **If a city is provided** (e.g., "find restaurants in Penang", "best food in Singapore"): Use that city
- **If no city is specified** (e.g., "recommend some restaurants", "find good places to eat"): **Ask the user for their city** before proceeding

> Example prompt: *"I'd be happy to recommend some restaurants! What city would you like me to search in?"*

## 2. Search for Restaurant Data

Use the `web_search` tool to find highly-rated restaurants in the specified city.
- Search queries should target Google Reviews, Yelp, TripAdvisor, and local food blogs
- Include keywords like "best restaurants [CITY]", "top rated restaurants [CITY]", "highest rated restaurants [CITY] 2024", "best restaurants [CITY] Google Reviews"
- Retrieve at least 10 results to ensure comprehensive coverage

## 3. Fetch Detailed Information

For each promising restaurant found:
- Use `web_fetch` with mode="text" to extract readable content from review sites
- Prioritize Google Maps results, official websites, and reliable review platforms
- Extract: restaurant name, rating (out of 5), number of reviews, address, phone number, cuisine type, price range, and notable review excerpts

## 4. Compile Chat Response

Present the findings directly in chat response with the following structure:

```
# Top-Rated Restaurants in [CITY]

*Report generated: [DATE]*

## Summary
Found [N] restaurants with ratings of X.X+ stars, averaging X.X stars across [N] total reviews.

---

## Restaurant Name 1
- **Rating:** X.X/5 ⭐ ([N] reviews)
- **Cuisine:** [Type]
- **Price Range:** $/$$/$$$/$$$$ (or local currency range)
- **Address:** [Full Address]
- **Phone:** [Contact Number]
- **Review Highlight:** *"[Notable review excerpt]"*

---

## Restaurant Name 2
...

---

## Methodology
Data compiled from Google Reviews, TripAdvisor, Yelp, and local food publications. Last updated: [DATE].
```

### Response Requirements:
- Include at least 8-10 top-rated restaurants from the specified city
- Sort by rating (highest first), then by number of reviews
- Include a brief summary at the top with key statistics
- Add a "Methodology" section explaining data sources

## 5. Edge Cases & Error Handling

- **No city provided:** Ask the user which city to search
- **City specified but no results found:** Inform the user and suggest alternative search terms, nearby areas, or ask if they'd like to try a different city
- **Incomplete data:** Note "information not available" for missing fields
- **Conflicting ratings:** Cross-reference multiple sources and note discrepancies
- **Outdated info:** Aim for reviews from the last 12 months; note if older data is used

## Example Output

When asked "What are the best restaurants in Bangkok?", respond in chat:

```
# Top-Rated Restaurants in Bangkok

*Report generated: April 8, 2026*

## Summary
Found 10 restaurants with ratings of 4.5+ stars, averaging 4.7 stars across [N] total reviews.

---

## 1. [PERSONAL_NAME]
- **Rating:** 4.8/5 ⭐ (1,245 reviews)
- **Cuisine:** Indian / Fine Dining
- **Price Range:** $$$$
- **Address:** [ADDRESS]
- **Phone:** [PHONE_NUMBER]
- **Review Highlight:** *"Progressive Indian cuisine with playful presentations and bold flavors..."*

...

---

## Methodology
Data compiled from Google Reviews, TripAdvisor, Yelp, and local food publications. Last updated: April 2026.
```
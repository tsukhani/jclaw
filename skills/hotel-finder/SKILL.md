---
name: hotel-finder
description: Search the web for best hotels in a specified city and provide findings directly in chat with names, ratings, prices, amenities, and contact information.
version: 1.0.0
author: main
tools: [web_search, web_fetch]
commands: []
icon: 🏨
---
# Hotel Finder

When asked to find hotels in a city:

## 1. Identify the Target City

**Check if a city was specified** in the user's request.

- **If a city is provided** (e.g., "find hotels in Penang", "best hotels in Singapore", "Where to stay in Bali"): Use that city
- **If no city is specified** (e.g., "recommend a hotel", "find good places to stay"): **Ask the user for their city** before proceeding

> Example prompt: *"I'd be happy to recommend some hotels! What city are you looking to stay in?"*

## 2. Search for Hotel Data

Use the `web_search` tool to find the best hotels in the specified city.
- Search queries should target Booking.com, TripAdvisor, Google Hotels, Agoda, Expedia, and travel blogs
- Include keywords like "best hotels [CITY]", "top rated hotels [CITY]", "luxury hotels [CITY]", "best value hotels [CITY] 2024", "best hotels [CITY] Booking.com"
- Also search for: "[CITY] hotel reviews TripAdvisor", "[CITY] best boutique hotels", "[CITY] 5 star hotels"
- Retrieve at least 10 results to ensure comprehensive coverage

## 3. Fetch Detailed Information

For each promising hotel found:
- Use `web_fetch` with mode="text" to extract readable content from hotel booking sites, review platforms, and official hotel websites
- Prioritize Booking.com, Agoda, TripAdvisor, Google Hotels, and official hotel websites
- Extract: hotel name, star rating, guest rating (out of 10 or 5), number of reviews, approximate price per night (in USD or local currency), address, phone number, key amenities (pool, spa, gym, free WiFi, breakfast, etc.), and notable review excerpts

## 4. Compile Chat Response

Present the findings directly in chat response with the following structure:

```
# Best Hotels in [CITY]

*Report generated: [DATE]*

## Summary
Found [N] hotels with ratings of X.X+, averaging X.X across [N] total reviews. Price range: $XX - $XXX per night.

---

## 1. Hotel Name 1
- **Rating:** X.X/10 ⭐ ([N] reviews)
- **Star Category:** ⭐⭐⭐⭐⭐ (5-star)
- **Price:** ~$XXX/night (est.)
- **Address:** [Full Address]
- **Phone:** [Contact Number]
- **Amenities:** [Key amenities: pool, spa, gym, free WiFi, breakfast included, etc.]
- **Review Highlight:** *"[Notable review excerpt]"*

---

## 2. Hotel Name 2
...

---

## Methodology
Data compiled from Booking.com, TripAdvisor, Agoda, Google Hotels, and travel publications. Prices are approximate and subject to change based on season and availability. Last updated: [DATE].
```

### Response Requirements:
- Include at least 8-10 top-rated hotels from the specified city
- Sort by guest rating (highest first), then by number of reviews
- Include a brief summary at the top with key statistics and price range
- Add a "Methodology" section explaining data sources
- Mark prices as approximate estimates — they fluctuate by season, demand, and room type

## 5. Edge Cases & Error Handling

- **No city provided:** Ask the user which city to search
- **City specified but no results found:** Inform the user and suggest alternative search terms, nearby areas, or ask if they'd like to try a different city
- **Incomplete data:** Note "information not available" for missing fields
- **Conflicting ratings:** Cross-reference multiple sources and note discrepancies
- **Outdated info:** Aim for reviews from the last 12 months; note if older data is used
- **Currency ambiguity:** If price data is unclear, provide a range or note "pricing varies" — never guess exact figures

## Example Output

When asked "What are the best hotels in Bali?", respond in chat:

```
# Best Hotels in Bali

*Report generated: May 20, 2026*

## Summary
Found 10 hotels with ratings of 8.5+, averaging 9.1 across 15,000+ total reviews. Price range: ~$80 - $500+ per night.

---

## 1. [HOTEL_NAME]
- **Rating:** 9.4/10 ⭐ (2,340 reviews)
- **Star Category:** ⭐⭐⭐⭐⭐ (5-star)
- **Price:** ~$450/night (est.)
- **Address:** [ADDRESS]
- **Phone:** [PHONE_NUMBER]
- **Amenities:** Infinity pool, spa, beachfront, free WiFi, 3 restaurants, yoga pavilion
- **Review Highlight:** *"Unforgettable stay with impeccable service and stunning sunset views..."*

...

---

## Methodology
Data compiled from Booking.com, TripAdvisor, Agoda, Google Hotels, and travel publications. Prices are approximate and subject to change. Last updated: May 2026.
```
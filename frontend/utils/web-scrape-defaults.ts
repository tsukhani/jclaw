/**
 * What the backend uses when a web_scrape key is unset — WebScrapeSettings' defaults. Shared by the
 * Web Scrape settings panel and the New scrape form (JCLAW-1273), which pre-fills from them.
 */
export const WEB_SCRAPE_DEFAULTS = {
  'web_scrape.max-depth': '2',
  'web_scrape.language': 'en',
  'web_scrape.respect-robots': 'true',
  'web_scrape.seed-from-sitemap': 'true',
  'web_scrape.job.max-pages': '500',
  'web_scrape.job.max-minutes': '60',
} as const

export type WebScrapeDefaultKey = keyof typeof WEB_SCRAPE_DEFAULTS

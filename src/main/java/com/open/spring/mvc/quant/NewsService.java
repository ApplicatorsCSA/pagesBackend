package com.open.spring.mvc.quant;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Minimal news + sentiment service that fits the Bank API.
 *
 * What it does (server-side):
 * - Provides a "sentiment snapshot" payload:
 *     overall_market_sentiment
 *     category sentiment: economic / geopolitical / social
 *     news_volume_24h
 *     negative_news_ratio
 *     recent headlines list
 *
 * IMPORTANT:
 * - This is intentionally "stubbed" so your backend compiles and your frontend has real endpoints.
 * - Later you can swap the internals to call a real provider (NewsAPI, GDELT, Finnhub, etc.)
 *   without changing BankApiController.
 */
@Service
public class NewsService {

    // Simple in-memory cache so you don't recompute on every request
    private final Map<String, SentimentSnapshot> cache = new HashMap<>();
    private final Map<String, Long> cacheTimeMs = new HashMap<>();
    private static final long TTL_MS = 60_000; // 60s

    public SentimentSnapshot getSentimentSnapshot(String ticker) {
        if (ticker == null || ticker.isBlank()) ticker = "SPY";
        ticker = ticker.toUpperCase(Locale.ROOT);

        long now = System.currentTimeMillis();
        Long last = cacheTimeMs.get(ticker);
        if (last != null && (now - last) < TTL_MS) {
            return cache.get(ticker);
        }

        // ---- STUBBED NEWS ----
        // If you later add a real news provider, replace buildStubSnapshot(...) only.
        SentimentSnapshot snap = buildStubSnapshot(ticker);

        cache.put(ticker, snap);
        cacheTimeMs.put(ticker, now);
        return snap;
    }

    private SentimentSnapshot buildStubSnapshot(String ticker) {
        // Deterministic "random" based on ticker so it looks stable per symbol
        long seed = Math.abs(ticker.hashCode());
        Random r = new Random(seed ^ (System.currentTimeMillis() / (5 * 60_000))); // changes every ~5 min

        // Sentiments in [-1, 1]
        double overall = clamp((r.nextDouble() * 2) - 1, -1, 1);
        double econ = clamp(overall * 0.6 + ((r.nextDouble() * 2) - 1) * 0.4, -1, 1);
        double geo  = clamp(overall * 0.4 + ((r.nextDouble() * 2) - 1) * 0.6, -1, 1);
        double soc  = clamp(overall * 0.5 + ((r.nextDouble() * 2) - 1) * 0.5, -1, 1);

        int volume = 10 + r.nextInt(40); // 10..49
        double negRatio = clamp(0.2 + r.nextDouble() * 0.5, 0, 1);

        List<Headline> headlines = new ArrayList<>();
        headlines.add(new Headline(nowIso(), ticker + " market update: mixed signals as volume shifts", overall));
        headlines.add(new Headline(nowIso(), "Macro watch: rates and inflation expectations influence " + ticker, econ));
        headlines.add(new Headline(nowIso(), "Geopolitics: risk sentiment swings across equities", geo));
        headlines.add(new Headline(nowIso(), "Sector rotation: investors reposition around " + ticker, overall));
        headlines.add(new Headline(nowIso(), "Social sentiment: retail chatter ticks " + (soc >= 0 ? "up" : "down"), soc));

        Map<String, Double> categories = new LinkedHashMap<>();
        categories.put("economic", econ);
        categories.put("geopolitical", geo);
        categories.put("social", soc);

        SentimentSnapshot snap = new SentimentSnapshot();
        snap.setTicker(ticker);
        snap.setGeneratedAt(nowIso());
        snap.setOverallMarketSentiment(overall);
        snap.setNewsVolume24h(volume);
        snap.setNegativeNewsRatio(negRatio);
        snap.setCategorySentiment(categories);
        snap.setHeadlines(headlines);

        // short plain-English summary for the frontend to show
        snap.setSummary(buildSummary(snap));
        return snap;
    }

    private String buildSummary(SentimentSnapshot s) {
        String dir = s.getOverallMarketSentiment() > 0.15 ? "positive"
                : s.getOverallMarketSentiment() < -0.15 ? "negative"
                : "neutral";

        String worstCat = null;
        double worst = Double.POSITIVE_INFINITY;

        for (Map.Entry<String, Double> e : s.getCategorySentiment().entrySet()) {
            if (e.getValue() < worst) {
                worst = e.getValue();
                worstCat = e.getKey();
            }
        }

        return "News sentiment for " + s.getTicker() + " is " + dir +
                " (score " + round3(s.getOverallMarketSentiment()) + "). " +
                "Most negative category: " + worstCat + " (" + round3(worst) + "). " +
                "Volume last 24h: " + s.getNewsVolume24h() + ".";
    }

    private String nowIso() {
        return Instant.now().toString();
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private String round3(double v) {
        return String.format(Locale.US, "%.3f", v);
    }
}

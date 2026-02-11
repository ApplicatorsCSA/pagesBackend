package com.open.spring.mvc.quant;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * JSON payload returned by /bank/quant/news/sentiment
 */
@Data
public class SentimentSnapshot {
    private String ticker;
    private String generatedAt;

    // [-1, 1]
    private double overallMarketSentiment;

    // last ~24h (stubbed)
    private int newsVolume24h;

    // [0,1]
    private double negativeNewsRatio;

    // economic/geopolitical/social -> [-1,1]
    private Map<String, Double> categorySentiment;

    private List<Headline> headlines;

    // plain English string for frontend
    private String summary;
}

package com.open.spring.mvc.quant;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Response payload for:
 * POST /bank/quant/ml/train
 *
 * Mirrors the main things your Streamlit app shows:
 * - accuracy-ish metrics (not perfect finance metrics, but consistent)
 * - actual vs predicted series for plotting
 * - future predictions for horizon days
 */
@Data
public class MLTrainResponse {

    private String ticker;
    private String modelType;

    // Metrics
    private double accuracy; // directional accuracy (up/down)
    private double mae;
    private double rmse;
    private double r2;

    // Plot series (aligned)
    private List<String> dates = new ArrayList<>();
    private List<Double> actual = new ArrayList<>();
    private List<Double> predicted = new ArrayList<>();

    // Future forecast
    private List<String> futureDates = new ArrayList<>();
    private List<Double> futurePredictions = new ArrayList<>();

    // Debug/notes
    private String note;
}

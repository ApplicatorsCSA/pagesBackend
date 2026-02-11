package com.open.spring.mvc.quant;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class BacktestResult {

    // Chart series
    private List<String> dates = new ArrayList<>();
    private List<Double> portfolioValue = new ArrayList<>();
    private List<Double> benchmarkValue = new ArrayList<>(); // buy & hold

    // Returns series (daily %)
    private List<Double> returns = new ArrayList<>();

    // Trades list
    private List<Trade> trades = new ArrayList<>();

    // Quick metrics
    private double totalReturnPct;
    private double maxDrawdownPct;
    private double winRatePct;

    private String note;

    @Data
    public static class Trade {
        private String date;
        private String side;     // BUY / SELL
        private int qty;
        private double price;
        private double cashAfter;
        private double positionAfter;
    }
}

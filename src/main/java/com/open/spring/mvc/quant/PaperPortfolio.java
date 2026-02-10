package com.open.spring.mvc.quant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class PaperPortfolio {

    private double cashBalance;

    // ticker -> position
    private Map<String, Position> positions = new HashMap<>();

    private List<OrderRecord> orders = new ArrayList<>();

    @Data
    public static class Position {
        private String ticker;
        private int qty;
        private double avgCost;
        private double marketPrice;
        private double marketValue;
        private double unrealizedPnL;
    }

    @Data
    public static class OrderRecord {
        private String time;
        private String ticker;
        private String side;
        private int qty;
        private double price;
        private double totalCost;
    }
}

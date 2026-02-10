package com.open.spring.mvc.bank;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class Bank {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private String uid;

    @OneToOne
    @JoinColumn(name = "person_id", referencedColumnName = "id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JsonIgnore
    private Person person; // One-to-One relationship with the Person entity

    private double balance;
    private double loanAmount;

    // Personalized daily interest rate (%)
    private double dailyInterestRate = 5.0; // Default

    // Risk category (0=low, 1=medium, 2=high)
    private int riskCategory = 1;

    // Track transaction history for ML features
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, List<List<Object>>> profitMap = new HashMap<>();

    // Store ML feature importance for explainability
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Double> featureImportance = new HashMap<>();

    // Track NPC progress
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private LinkedHashMap<String, Boolean> npcProgress = new LinkedHashMap<>();

    // ==========================
    // GAME STATE (NEW)
    // ==========================
    private int xp = 0;
    private int level = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private LinkedHashMap<String, Boolean> questProgress = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> lastRunStats = new HashMap<>();

    public Bank(Person person) {
        this.person = person;
        this.person.setBanks(this);
        this.uid = person.getUid();

        this.loanAmount = 0.0;
        this.balance = 100000.0;

        this.profitMap = new HashMap<>();
        this.featureImportance = new HashMap<>();
        this.npcProgress = new LinkedHashMap<>();

        // NEW game fields
        this.xp = 0;
        this.level = 1;
        this.questProgress = new LinkedHashMap<>();
        this.lastRunStats = new HashMap<>();

        initializeNpcProgress();
        initializeQuestProgress();
        initializeFeatureImportance();
    }

    public String getUsername() {
        return person != null ? person.getName() : null;
    }

    private void initializeNpcProgress() {
        this.npcProgress.put("Stock-NPC", true);
        this.npcProgress.put("Casino-NPC", false);
        this.npcProgress.put("Fidelity", false);
        this.npcProgress.put("Schwab", false);
        this.npcProgress.put("Mining-NPC", false);
        this.npcProgress.put("Crypto-NPC", false);
        this.npcProgress.put("Bank-NPC", false);
    }

    // NEW quests for quant game
    private void initializeQuestProgress() {
        this.questProgress.put("Run Backtest", false);
        this.questProgress.put("Beat Buy&Hold", false);
        this.questProgress.put("Sharpe >= 1.0", false);
        this.questProgress.put("Train ML Model", false);
        this.questProgress.put("Use News Boost", false);
    }

    private void initializeFeatureImportance() {
        this.featureImportance.put("casino_frequency", 0.42);
        this.featureImportance.put("profit_loss_ratio", 0.38);
        this.featureImportance.put("recent_activity", 0.35);
        this.featureImportance.put("loan_balance_ratio", 0.25);
        this.featureImportance.put("loan_history", 0.20);
        this.featureImportance.put("stock_activity", -0.30);
        this.featureImportance.put("crypto_activity", -0.28);
        this.featureImportance.put("volatility", 0.15);
        this.featureImportance.put("balance_trend", 0.22);
    }

    // ==========================
    // GAME HELPERS
    // ==========================
    public void addXp(int xpGained) {
        if (xpGained <= 0) return;
        this.xp += xpGained;
        this.level = Math.max(1, (this.xp / 200) + 1); // simple leveling rule
    }

    // ==========================
    // BALANCE METHODS
    // ==========================

    // (NEW) Overload so old code calling setBalance(x) still compiles
    public void setBalance(double updatedBalance) {
        setBalance(updatedBalance, "admin_update");
    }

    // Main balance setter that records profit history
    public double setBalance(double updatedBalance, String source) {
        Double profit = updatedBalance - this.balance;
        this.balance = updatedBalance;

        System.out.println("Profit: " + profit);

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timestamp = dateFormat.format(new Date());
        updateProfitMap(source, timestamp, profit);

        return this.balance;
    }

    public void updateProfitMap(String category, String time, double profit) {
        if (this.profitMap == null) {
            this.profitMap = new HashMap<>();
        }

        List<Object> transaction = Arrays.asList(time, profit);
        this.profitMap.computeIfAbsent(category, k -> new ArrayList<>()).add(transaction);
    }

    public List<List<Object>> getProfitByCategory(String category) {
        return this.profitMap.getOrDefault(category, new ArrayList<>());
    }

    // ==========================
    // LOANS
    // ==========================
    public void requestLoan(double loanAmount) {
        this.loanAmount += loanAmount;
        balance += loanAmount;

        // Re-assess risk using ML model
        assessRiskUsingML();
    }

    public void repayLoan(double repaymentAmount) {
        if (repaymentAmount <= 0) {
            throw new IllegalArgumentException("Repayment amount must be positive");
        }
        if (balance < repaymentAmount) {
            throw new IllegalArgumentException("Insufficient balance for this repayment");
        }
        if (repaymentAmount > loanAmount) {
            throw new IllegalArgumentException("Repayment amount exceeds the loan balance");
        }

        balance -= repaymentAmount;
        loanAmount -= repaymentAmount;

        String timestamp = Instant.now().toString();
        this.updateProfitMap("loan_repayment", timestamp, -repaymentAmount);

        assessRiskUsingML();
    }

    public double dailyInterestCalculation() {
        return loanAmount * (dailyInterestRate / 100);
    }

    // ==========================
    // ML RISK + EXPLAINABILITY
    // ==========================
    public void assessRiskUsingML() {
        double baseRate = LoanRiskCalculator.calculateDailyInterestRate(this);
        double ensembleRate = LoanRiskCalculator.ensembleInterestRate(this);

        // 70% ensemble, 30% base
        double finalRate = (ensembleRate * 0.7) + (baseRate * 0.3);

        this.dailyInterestRate = finalRate;
        this.riskCategory = LoanRiskCalculator.classifyRiskCategory(this);

        updateFeatureImportance();
    }

    private void updateFeatureImportance() {
        Random random = new Random();

        boolean hasCasino = false;
        boolean hasStocks = false;
        boolean hasCrypto = false;

        for (String key : profitMap.keySet()) {
            if (key.startsWith("casino_")) hasCasino = true;
            if (key.equals("stocks")) hasStocks = true;
            if (key.equals("cryptomining")) hasCrypto = true;
        }

        if (hasCasino) {
            double variation = (random.nextDouble() * 0.1) - 0.05;
            featureImportance.put(
                "casino_frequency",
                Math.max(0.3, Math.min(0.5, featureImportance.get("casino_frequency") + variation))
            );
        }

        if (hasStocks) {
            double variation = (random.nextDouble() * 0.08) - 0.04;
            featureImportance.put(
                "stock_activity",
                Math.min(-0.2, Math.max(-0.4, featureImportance.get("stock_activity") + variation))
            );
        }

        if (hasCrypto) {
            double variation = (random.nextDouble() * 0.08) - 0.04;
            featureImportance.put(
                "crypto_activity",
                Math.min(-0.2, Math.max(-0.4, featureImportance.get("crypto_activity") + variation))
            );
        }

        if (loanAmount > 0) {
            double loanToBalanceRatio = balance > 0 ? loanAmount / balance : 2.0;
            if (loanToBalanceRatio > 0.8) {
                featureImportance.put(
                    "loan_history",
                    Math.min(0.3, featureImportance.get("loan_history") + 0.05)
                );
            }
        }
    }

    // ==========================
    // GAME ACTIVITIES (SIM)
    // ==========================
    public double playCasinoGame(String gameType, double betAmount) {
        if (betAmount <= 0 || balance < betAmount) {
            return 0.0;
        }

        if (!gameType.startsWith("casino_")) {
            gameType = "casino_" + gameType;
        }

        double winChance;
        double payoutMultiplier;

        switch (gameType) {
            case "casino_dice":
                winChance = 0.48;
                payoutMultiplier = 2.0;
                break;
            case "casino_poker":
                winChance = 0.45;
                payoutMultiplier = 2.2;
                break;
            case "casino_mines":
                winChance = 0.40;
                payoutMultiplier = 2.5;
                break;
            case "casino_blackjack":
                winChance = 0.47;
                payoutMultiplier = 2.1;
                break;
            default:
                winChance = 0.48;
                payoutMultiplier = 2.0;
        }

        this.balance -= betAmount;

        double profit;
        if (Math.random() < winChance) {
            profit = betAmount * payoutMultiplier;
            this.balance += profit;
            profit -= betAmount; // net profit
        } else {
            profit = -betAmount;
        }

        String timestamp = Instant.now().toString();
        this.updateProfitMap(gameType, timestamp, profit);

        assessRiskUsingML();
        return profit;
    }

    public double investInStocks(double investmentAmount) {
        if (investmentAmount <= 0 || balance < investmentAmount) {
            return 0.0;
        }

        this.balance -= investmentAmount;

        double returnRange = 0.25; // +/- 25%

        double returnMultiplier = 1.0 + (Math.random() * returnRange * 2) - returnRange;
        double returns = investmentAmount * returnMultiplier;

        this.balance += returns;

        double profit = returns - investmentAmount;

        String timestamp = Instant.now().toString();
        this.updateProfitMap("stocks", timestamp, profit);

        assessRiskUsingML();
        return profit;
    }

    public double mineCrypto(double electricityCost) {
        if (electricityCost <= 0 || balance < electricityCost) {
            return 0.0;
        }

        this.balance -= electricityCost;

        double baseReturn = electricityCost * 1.1; // 10% base profit
        double bonusChance = 0.15;

        double returns = baseReturn;
        if (Math.random() < bonusChance) {
            returns += electricityCost * Math.random();
        }

        this.balance += returns;

        double profit = returns - electricityCost;

        String timestamp = Instant.now().toString();
        this.updateProfitMap("cryptomining", timestamp, profit);

        assessRiskUsingML();
        return profit;
    }

    // ==========================
    // EXPLAINABILITY HELPERS
    // ==========================
    public String getRiskCategoryString() {
        switch (riskCategory) {
            case 0: return "Low Risk";
            case 1: return "Medium Risk";
            case 2: return "High Risk";
            default: return "Unknown Risk";
        }
    }

    public List<String> getFeatureImportanceExplanations() {
        List<String> explanations = new ArrayList<>();

        for (Map.Entry<String, Double> feature : featureImportance.entrySet()) {
            String impact = feature.getValue() > 0 ? "increases" : "decreases";
            String magnitude = Math.abs(feature.getValue()) > 0.3 ? "significantly" : "slightly";

            explanations.add(String.format(
                "Your %s %s %s your interest rate",
                formatFeatureName(feature.getKey()),
                magnitude,
                impact
            ));
        }

        return explanations;
    }

    private String formatFeatureName(String featureName) {
        return featureName
            .replace("_", " ")
            .replace("casino frequency", "casino gaming activity")
            .replace("profit loss ratio", "gambling win/loss record")
            .replace("recent activity", "recent gambling activity")
            .replace("loan balance ratio", "loan-to-balance ratio")
            .replace("loan history", "loan repayment history")
            .replace("stock activity", "stock market investments")
            .replace("crypto activity", "cryptocurrency mining")
            .replace("balance trend", "account balance history");
    }

    // ==========================
    // INIT
    // ==========================
    public static Bank[] init(Person[] persons) {
        ArrayList<Bank> bankList = new ArrayList<>();

        for (Person person : persons) {
            Bank bank = new Bank(person);
            bank.assessRiskUsingML();
            bankList.add(bank);
        }

        return bankList.toArray(new Bank[0]);
    }
}

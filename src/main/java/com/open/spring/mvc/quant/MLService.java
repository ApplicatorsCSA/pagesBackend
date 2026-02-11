package com.open.spring.mvc.quant;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.open.spring.mvc.bank.BankApiController;

/**
 * Lightweight ML engine:
 * - Supports "linear_regression" and "random_forest" (RF behaves like LR for now)
 * - Uses returns + volatility + news sentiment as features
 */
@Service
public class MLService {

    public MLTrainResponse trainAndPredict(
            BankApiController.TrainRequest req,
            List<Bar> bars,
            Object indicatorsMaybe,
            SentimentSnapshot news
    ) {
        String ticker = safeUpper(req.ticker);
        String modelType = safeLower(req.modelType);
        int horizon = Math.max(1, req.horizon);
        double testSize = clamp(req.testSize, 0.1, 0.5);

        MLTrainResponse out = new MLTrainResponse();
        out.setTicker(ticker);
        out.setModelType(modelType);

        if (bars == null || bars.size() < 80) {
            out.setNote("Not enough market data. Need at least ~80 daily bars.");
            return out;
        }

        // sort by time ascending
        bars = new ArrayList<>(bars);
        bars.sort(Comparator.comparing(Bar::getTime));

        // Convert bar times -> LocalDate
        List<LocalDate> dates = new ArrayList<>();
        List<Double> close = new ArrayList<>();
        for (Bar b : bars) {
            dates.add(b.getTime().atZone(ZoneOffset.UTC).toLocalDate());
            close.add(b.getClose());
        }

        double sentiment = (news != null) ? news.getOverallMarketSentiment() : 0.0;

        // Build supervised dataset
        List<double[]> X = new ArrayList<>();
        List<Double> Y = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();

        for (int i = 30; i < close.size() - horizon; i++) {
            double c0 = close.get(i);
            double c1 = close.get(i - 1);
            if (c0 <= 0 || c1 <= 0) continue;

            double r1 = (c0 / c1) - 1.0;
            double r5 = (close.get(i) / close.get(i - 5)) - 1.0;
            double r20 = (close.get(i) / close.get(i - 20)) - 1.0;
            double vol10 = stdDevReturns(close, i - 10, i);

            double[] feat = new double[]{r1, r5, r20, vol10, sentiment};
            double future = (close.get(i + horizon) / close.get(i)) - 1.0;

            X.add(feat);
            Y.add(future);
            idx.add(i);
        }

        if (X.size() < 50) {
            out.setNote("Not enough usable training rows after feature building.");
            return out;
        }

        int n = X.size();
        int split = (int) Math.floor(n * (1.0 - testSize));
        split = Math.max(10, Math.min(split, n - 10));

        if ("lstm".equals(modelType)) {
            out.setNote("LSTM not implemented. Use linear_regression or random_forest.");
            modelType = "linear_regression";
            out.setModelType(modelType);
        }

        // Train (LR)
        double[] w = fitLinearRegression(X.subList(0, split), Y.subList(0, split));

        // Predict on test set
        List<Double> yTrue = new ArrayList<>();
        List<Double> yPred = new ArrayList<>();
        List<LocalDate> yDates = new ArrayList<>();

        for (int j = split; j < n; j++) {
            double pred = dot(w, X.get(j));
            yPred.add(pred);
            yTrue.add(Y.get(j));
            yDates.add(dates.get(idx.get(j)));
        }

        out.setMae(mae(yTrue, yPred));
        out.setRmse(rmse(yTrue, yPred));
        out.setR2(r2(yTrue, yPred));
        out.setAccuracy(directionalAccuracy(yTrue, yPred));

        // Build chart series (actual vs predicted prices)
        for (int k = 0; k < yDates.size(); k++) {
            int barIndex = idx.get(split + k);

            double base = close.get(barIndex);
            double actualPrice = close.get(barIndex + horizon);
            double predictedPrice = base * (1.0 + yPred.get(k));

            out.getDates().add(yDates.get(k).toString());
            out.getActual().add(round2(actualPrice));
            out.getPredicted().add(round2(predictedPrice));
        }

        // Future forecast from last bar
        LocalDate lastDate = dates.get(dates.size() - 1);
        double lastClose = close.get(close.size() - 1);

        int i = close.size() - 1;
        if (i >= 30) {
            double r1 = (close.get(i) / close.get(i - 1)) - 1.0;
            double r5 = (close.get(i) / close.get(i - 5)) - 1.0;
            double r20 = (close.get(i) / close.get(i - 20)) - 1.0;
            double vol10 = stdDevReturns(close, i - 10, i);

            double[] feat = new double[]{r1, r5, r20, vol10, sentiment};

            double predReturn = dot(w, feat);

            for (int d = 1; d <= horizon; d++) {
                LocalDate fd = lastDate.plusDays(d);
                out.getFutureDates().add(fd.toString());
                out.getFuturePredictions().add(round2(lastClose * (1.0 + predReturn)));
            }
        }

        out.setNote("Model trained with lightweight linear regression features.");
        return out;
    }

    // ----------------- Helpers -----------------

    private String safeUpper(String s) {
        if (s == null) return "SPY";
        s = s.trim();
        if (s.isEmpty()) return "SPY";
        return s.toUpperCase(Locale.ROOT);
    }

    private String safeLower(String s) {
        if (s == null) return "linear_regression";
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "linear_regression";
        return s;
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // w = (X'X)^-1 X'y
    private double[] fitLinearRegression(List<double[]> X, List<Double> y) {
        int m = X.size();
        int p = X.get(0).length + 1;

        double[][] XtX = new double[p][p];
        double[] Xty = new double[p];

        for (int i = 0; i < m; i++) {
            double[] row = X.get(i);

            double[] xb = new double[p];
            xb[0] = 1.0;
            System.arraycopy(row, 0, xb, 1, p - 1);

            double yi = y.get(i);

            for (int a = 0; a < p; a++) {
                Xty[a] += xb[a] * yi;
                for (int b = 0; b < p; b++) {
                    XtX[a][b] += xb[a] * xb[b];
                }
            }
        }

        return solveGaussian(XtX, Xty);
    }

    private double dot(double[] w, double[] x) {
        double s = w[0];
        for (int i = 0; i < x.length; i++) {
            s += w[i + 1] * x[i];
        }
        return s;
    }

    private double[] solveGaussian(double[][] A, double[] b) {
        int n = b.length;
        double[][] M = new double[n][n];
        double[] B = new double[n];
        double[] x = new double[n];

        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            B[i] = b[i];
        }

        for (int k = 0; k < n; k++) {
            int pivot = k;
            for (int i = k + 1; i < n; i++) {
                if (Math.abs(M[i][k]) > Math.abs(M[pivot][k])) pivot = i;
            }

            if (Math.abs(M[pivot][k]) < 1e-12) {
                return new double[n];
            }

            double[] tmp = M[k];
            M[k] = M[pivot];
            M[pivot] = tmp;

            double tB = B[k];
            B[k] = B[pivot];
            B[pivot] = tB;

            for (int i = k + 1; i < n; i++) {
                double f = M[i][k] / M[k][k];
                B[i] -= f * B[k];
                for (int j = k; j < n; j++) {
                    M[i][j] -= f * M[k][j];
                }
            }
        }

        for (int i = n - 1; i >= 0; i--) {
            double sum = B[i];
            for (int j = i + 1; j < n; j++) sum -= M[i][j] * x[j];
            x[i] = sum / M[i][i];
        }

        return x;
    }

    private double mae(List<Double> y, List<Double> p) {
        double s = 0;
        for (int i = 0; i < y.size(); i++) s += Math.abs(y.get(i) - p.get(i));
        return s / Math.max(1, y.size());
    }

    private double rmse(List<Double> y, List<Double> p) {
        double s = 0;
        for (int i = 0; i < y.size(); i++) {
            double d = y.get(i) - p.get(i);
            s += d * d;
        }
        return Math.sqrt(s / Math.max(1, y.size()));
    }

    private double r2(List<Double> y, List<Double> p) {
        double mean = 0;
        for (double v : y) mean += v;
        mean /= Math.max(1, y.size());

        double ssTot = 0, ssRes = 0;
        for (int i = 0; i < y.size(); i++) {
            double yi = y.get(i);
            double pi = p.get(i);
            ssTot += (yi - mean) * (yi - mean);
            ssRes += (yi - pi) * (yi - pi);
        }

        if (ssTot < 1e-12) return 0;
        return 1.0 - (ssRes / ssTot);
    }

    private double directionalAccuracy(List<Double> y, List<Double> p) {
        int correct = 0;
        for (int i = 0; i < y.size(); i++) {
            boolean upTrue = y.get(i) >= 0;
            boolean upPred = p.get(i) >= 0;
            if (upTrue == upPred) correct++;
        }
        return (double) correct / Math.max(1, y.size());
    }

    private double stdDevReturns(List<Double> close, int startIdx, int endIdxInclusive) {
        startIdx = Math.max(1, startIdx);
        endIdxInclusive = Math.min(endIdxInclusive, close.size() - 1);

        if (endIdxInclusive - startIdx < 2) return 0.0;

        List<Double> rets = new ArrayList<>();
        for (int i = startIdx; i <= endIdxInclusive; i++) {
            double c0 = close.get(i);
            double c1 = close.get(i - 1);
            if (c0 > 0 && c1 > 0) rets.add((c0 / c1) - 1.0);
        }

        if (rets.size() < 2) return 0.0;

        double mean = 0;
        for (double r : rets) mean += r;
        mean /= rets.size();

        double var = 0;
        for (double r : rets) {
            double d = r - mean;
            var += d * d;
        }

        var /= (rets.size() - 1);
        return Math.sqrt(var);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

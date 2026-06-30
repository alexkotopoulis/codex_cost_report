package com.example.codexcost;

import java.util.Locale;
import java.util.Map;

final class Pricing {
    private static final String DEFAULT_MODEL = "gpt-5.5";
    static final double DEFAULT_CREDIT_COST = 0.065d;
    private static final double CREDIT_UNIT_COST = 0.04d;

    /*
     * USD per 1M tokens from the OpenAI API pricing page. The gpt-5.5 default
     * uses the user's requested factors for this report.
     */
    private static final Map<String, PriceRate> RATES = Map.ofEntries(
            Map.entry("gpt-5.5", new PriceRate("gpt-5.5", 5.00, 0.50, 30.00, "OpenAI pricing page / user-selected factors")),
            Map.entry("gpt-5.5-pro", new PriceRate("gpt-5.5-pro", 15.00, 0.0, 90.00, "OpenAI pricing page")),
            Map.entry("gpt-5.4", new PriceRate("gpt-5.4", 2.50, 0.25, 11.25, "OpenAI pricing page, long context")),
            Map.entry("gpt-5.4-mini", new PriceRate("gpt-5.4-mini", 0.375, 0.0375, 2.25, "OpenAI pricing page")),
            Map.entry("gpt-5.4-nano", new PriceRate("gpt-5.4-nano", 0.10, 0.01, 0.625, "OpenAI pricing page")),
            Map.entry("gpt-5.4-pro", new PriceRate("gpt-5.4-pro", 30.00, 0.0, 135.00, "OpenAI pricing page, long context")),
            Map.entry("gpt-5.3-codex", new PriceRate("gpt-5.3-codex", 1.75, 0.175, 14.00, "OpenAI pricing page")),
            Map.entry("chat-latest", new PriceRate("chat-latest", 5.00, 0.50, 30.00, "OpenAI pricing page")));

    private Pricing() {
    }

    static double factorForCreditCost(double creditCostUsd) {
        return creditCostUsd / CREDIT_UNIT_COST;
    }

    static PriceRate rateFor(String model, double factor) {
        String normalized = normalize(model);
        return scale(RATES.getOrDefault(normalized, RATES.get(DEFAULT_MODEL).withModel(normalized)), factor);
    }

    static PriceRate rateFor(String model) {
        return rateFor(model, factorForCreditCost(DEFAULT_CREDIT_COST));
    }

    static CostBreakdown cost(TokenUsage usage, String model, double factor) {
        PriceRate rate = rateFor(model, factor);
        long cached = usage.cachedInputTokens();
        long uncachedInput = Math.max(0, usage.inputTokens() - cached);
        double inputCost = uncachedInput / 1_000_000d * rate.inputPerMillion();
        double cachedCost = cached / 1_000_000d * rate.cachedInputPerMillion();
        double outputCost = usage.outputTokens() / 1_000_000d * rate.outputPerMillion();
        double total = inputCost + cachedCost + outputCost;
        return new CostBreakdown(total, inputCost, cachedCost, outputCost, rate);
    }

    static CostBreakdown cost(TokenUsage usage, String model) {
        return cost(usage, model, factorForCreditCost(DEFAULT_CREDIT_COST));
    }

    private static PriceRate scale(PriceRate rate, double factor) {
        return new PriceRate(rate.model(), rate.inputPerMillion() * factor, rate.cachedInputPerMillion() * factor,
                rate.outputPerMillion() * factor, rate.source());
    }

    static String normalize(String model) {
        if (model == null || model.isBlank()) {
            return DEFAULT_MODEL;
        }
        return model.trim().toLowerCase(Locale.ROOT);
    }
}

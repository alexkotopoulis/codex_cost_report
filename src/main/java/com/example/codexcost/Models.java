package com.example.codexcost;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

record Overview(
        Instant generatedAt,
        String sessionsDir,
        TokenUsage totalUsage,
        CostBreakdown totalCost,
        int sessionCount,
        List<AggregateBucket> months,
        List<AggregateBucket> weeks,
        List<AggregateBucket> days,
        List<SessionSummary> sessions) {
}

record AggregateBucket(
        String key,
        String label,
        LocalDate start,
        LocalDate end,
        int sessionCount,
        TokenUsage usage,
        CostBreakdown cost) {
}

record SessionSummary(
        String id,
        Instant timestamp,
        LocalDate date,
        String cwd,
        String source,
        String modelProvider,
        String model,
        String reasoningLevel,
        String cliVersion,
        String path,
        TokenUsage usage,
        CostBreakdown cost,
        int tokenEvents,
        int parseErrors,
        String title) {
    static SessionSummary fromDetail(SessionDetail detail) {
        return new SessionSummary(detail.id(), detail.timestamp(), detail.date(), detail.cwd(), detail.source(),
                detail.modelProvider(), detail.model(), detail.reasoningLevel(), detail.cliVersion(), detail.path(),
                detail.usage(), detail.cost(), detail.tokenEvents(), detail.parseErrors(), detail.title());
    }
}

record SessionDetail(
        String id,
        Instant timestamp,
        LocalDate date,
        String cwd,
        String source,
        String modelProvider,
        String model,
        String reasoningLevel,
        String cliVersion,
        String path,
        TokenUsage usage,
        CostBreakdown cost,
        int tokenEvents,
        int parseErrors,
        String title,
        List<String> prompts) {
}

record TokenUsage(
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        long reasoningOutputTokens,
        long totalTokens) {
    static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0, 0);

    TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                inputTokens + other.inputTokens,
                cachedInputTokens + other.cachedInputTokens,
                outputTokens + other.outputTokens,
                reasoningOutputTokens + other.reasoningOutputTokens,
                totalTokens + other.totalTokens);
    }
}

record CostBreakdown(
        double totalUsd,
        double inputUsd,
        double cachedInputUsd,
        double outputUsd,
        PriceRate rate) {
    CostBreakdown plus(CostBreakdown other) {
        return new CostBreakdown(totalUsd + other.totalUsd, inputUsd + other.inputUsd,
                cachedInputUsd + other.cachedInputUsd, outputUsd + other.outputUsd, rate);
    }
}

record PriceRate(
        String model,
        double inputPerMillion,
        double cachedInputPerMillion,
        double outputPerMillion,
        String source) {
    PriceRate withModel(String model) {
        return new PriceRate(model, inputPerMillion, cachedInputPerMillion, outputPerMillion,
                source + " fallback");
    }
}

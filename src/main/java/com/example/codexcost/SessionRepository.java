package com.example.codexcost;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class SessionRepository {
    private final Path sessionsDir;
    private final ObjectMapper mapper;
    private final ZoneId zoneId;
    private final double pricingFactor;

    SessionRepository(Path sessionsDir, ObjectMapper mapper) {
        this(sessionsDir, mapper, Pricing.factorForCreditCost(Pricing.DEFAULT_CREDIT_COST));
    }

    SessionRepository(Path sessionsDir, ObjectMapper mapper, double pricingFactor) {
        this.sessionsDir = sessionsDir;
        this.mapper = mapper;
        this.zoneId = ZoneId.systemDefault();
        this.pricingFactor = pricingFactor;
    }

    Overview overview() {
        List<SessionDetail> sessions = loadSessions();
        TokenUsage total = sessions.stream()
                .map(SessionDetail::usage)
                .reduce(TokenUsage.ZERO, TokenUsage::plus);
        CostBreakdown totalCost = sessions.stream()
                .map(SessionDetail::cost)
                .reduce(new CostBreakdown(0, 0, 0, 0, Pricing.rateFor("gpt-5.5", pricingFactor)), CostBreakdown::plus);

        return new Overview(
                Instant.now(),
                sessionsDir.toString(),
                total,
                totalCost,
                sessions.size(),
                groupBy(sessions, BucketType.MONTH),
                groupBy(sessions, BucketType.WEEK),
                groupBy(sessions, BucketType.DAY),
                sessions.stream().map(SessionSummary::fromDetail).toList());
    }

    Optional<SessionDetail> session(String id) {
        return loadSessions().stream()
                .filter(session -> session.id().equals(id))
                .findFirst();
    }

    private List<SessionDetail> loadSessions() {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(sessionsDir)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jsonl"))
                    .map(this::parseSession)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(SessionDetail::timestamp).reversed())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan " + sessionsDir, e);
        }
    }

    private SessionDetail parseSession(Path path) {
        SessionAccumulator accumulator = new SessionAccumulator(path);

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                readLine(accumulator, line);
            }
        } catch (Exception e) {
            accumulator.parseErrors++;
        }

        return accumulator.toDetail(zoneId, pricingFactor);
    }

    private void readLine(SessionAccumulator accumulator, String line) throws IOException {
        JsonNode root = mapper.readTree(line);
        String type = root.path("type").asText("");
        Instant eventTime = parseInstant(root.path("timestamp").asText(null));
        if (eventTime != null) {
            accumulator.lastEvent = eventTime;
        }

        JsonNode payload = root.path("payload");
        if ("session_meta".equals(type)) {
            accumulator.id = text(payload, "id").or(() -> text(payload, "session_id")).orElse(accumulator.id);
            accumulator.timestamp = parseInstant(text(payload, "timestamp").orElse(null));
            accumulator.cwd = text(payload, "cwd").orElse(accumulator.cwd);
            accumulator.source = text(payload, "source").orElse(accumulator.source);
            accumulator.modelProvider = text(payload, "model_provider").orElse(accumulator.modelProvider);
            accumulator.cliVersion = text(payload, "cli_version").orElse(accumulator.cliVersion);
            return;
        }

        if ("turn_context".equals(type)) {
            accumulator.model = text(payload, "model")
                    .or(() -> text(payload.path("collaboration_mode").path("settings"), "model"))
                    .orElse(accumulator.model);
            accumulator.reasoningLevel = text(payload, "effort")
                    .or(() -> text(payload.path("collaboration_mode").path("settings"), "reasoning_effort"))
                    .orElse(accumulator.reasoningLevel);
            return;
        }

        if ("event_msg".equals(type)) {
            String payloadType = payload.path("type").asText("");
            if ("token_count".equals(payloadType)) {
                accumulator.tokenEvents++;
                TokenUsage usage = tokenUsage(payload.path("info").path("last_token_usage"));
                accumulator.usage = accumulator.usage.plus(usage);
            } else if ("user_message".equals(payloadType)) {
                addPrompt(accumulator, payload.path("message").asText(""));
            }
            return;
        }

        if ("response_item".equals(type) && "user".equals(payload.path("role").asText(""))) {
            JsonNode content = payload.path("content");
            if (content.isArray()) {
                content.forEach(item -> addPrompt(accumulator, item.path("text").asText("")));
            }
        }
    }

    private static void addPrompt(SessionAccumulator accumulator, String text) {
        String prompt = text.strip();
        if (prompt.isEmpty()
                || prompt.startsWith("<environment_context>")
                || prompt.startsWith("The following is the Codex agent history whose request action you are assessing.")
                || prompt.startsWith(">>> TRANSCRIPT START")) {
            return;
        }
        accumulator.prompts.add(prompt);
    }

    private static TokenUsage tokenUsage(JsonNode node) {
        return new TokenUsage(
                node.path("input_tokens").asLong(0),
                node.path("cached_input_tokens").asLong(0),
                node.path("output_tokens").asLong(0),
                node.path("reasoning_output_tokens").asLong(0),
                node.path("total_tokens").asLong(0));
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        String text = value.asText("");
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private List<AggregateBucket> groupBy(List<SessionDetail> sessions, BucketType type) {
        Map<String, MutableBucket> buckets = new LinkedHashMap<>();
        for (SessionDetail session : sessions) {
            BucketKey key = type.key(session.timestamp(), zoneId);
            MutableBucket bucket = buckets.computeIfAbsent(key.key(), ignored -> new MutableBucket(key));
            bucket.sessionCount++;
            bucket.usage = bucket.usage.plus(session.usage());
            bucket.cost = bucket.cost.plus(session.cost());
        }
        return buckets.values().stream()
                .map(MutableBucket::toBucket)
                .sorted(Comparator.comparing(AggregateBucket::start).reversed())
                .toList();
    }

    private enum BucketType {
        MONTH {
            @Override
            BucketKey key(Instant instant, ZoneId zoneId) {
                YearMonth month = YearMonth.from(instant.atZone(zoneId));
                return new BucketKey(month.toString(), month.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)),
                        month.atDay(1), month.atEndOfMonth());
            }
        },
        WEEK {
            @Override
            BucketKey key(Instant instant, ZoneId zoneId) {
                LocalDate date = instant.atZone(zoneId).toLocalDate();
                WeekFields fields = WeekFields.ISO;
                int week = date.get(fields.weekOfWeekBasedYear());
                int year = date.get(fields.weekBasedYear());
                LocalDate start = date.with(fields.dayOfWeek(), 1);
                return new BucketKey(year + "-W" + "%02d".formatted(week), "Week %02d, %d".formatted(week, year),
                        start, start.plusDays(6));
            }
        },
        DAY {
            @Override
            BucketKey key(Instant instant, ZoneId zoneId) {
                LocalDate date = instant.atZone(zoneId).toLocalDate();
                return new BucketKey(date.toString(), date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)),
                        date, date);
            }
        };

        abstract BucketKey key(Instant instant, ZoneId zoneId);
    }

    private static final class SessionAccumulator {
        private final Path path;
        private String id;
        private Instant timestamp;
        private Instant lastEvent;
        private String cwd = "";
        private String source = "";
        private String modelProvider = "";
        private String model = "gpt-5.5";
        private String reasoningLevel = "";
        private String cliVersion = "";
        private TokenUsage usage = TokenUsage.ZERO;
        private int tokenEvents;
        private int parseErrors;
        private final Set<String> prompts = new LinkedHashSet<>();

        private SessionAccumulator(Path path) {
            this.path = path;
            this.id = idFromFile(path);
        }

        private SessionDetail toDetail(ZoneId zoneId, double pricingFactor) {
            Instant effectiveTimestamp = timestamp != null ? timestamp : (lastEvent != null ? lastEvent : Instant.EPOCH);
            List<String> promptList = new ArrayList<>(prompts);
            String title = promptList.isEmpty() ? "(no prompt found)" : firstLine(promptList.get(0));
            CostBreakdown cost = Pricing.cost(usage, model, pricingFactor);
            return new SessionDetail(id, effectiveTimestamp, effectiveTimestamp.atZone(zoneId).toLocalDate(), cwd, source,
                    modelProvider, model, reasoningLevel, cliVersion, path.toString(), usage, cost, tokenEvents,
                    parseErrors, title, promptList);
        }

        private static String firstLine(String prompt) {
            String first = prompt.lines().findFirst().orElse(prompt).strip();
            return first.length() > 140 ? first.substring(0, 137) + "..." : first;
        }

        private static String idFromFile(Path path) {
            String name = path.getFileName().toString();
            if (name.endsWith(".jsonl")) {
                name = name.substring(0, name.length() - ".jsonl".length());
            }
            int lastDash = name.lastIndexOf('-');
            return lastDash >= 0 ? name.substring(lastDash + 1) : name;
        }
    }

    private record BucketKey(String key, String label, LocalDate start, LocalDate end) {
    }

    private static final class MutableBucket {
        private final BucketKey key;
        private int sessionCount;
        private TokenUsage usage = TokenUsage.ZERO;
        private CostBreakdown cost = new CostBreakdown(0, 0, 0, 0, Pricing.rateFor("gpt-5.5"));

        private MutableBucket(BucketKey key) {
            this.key = key;
        }

        private AggregateBucket toBucket() {
            return new AggregateBucket(key.key(), key.label(), key.start(), key.end(), sessionCount, usage,
                    cost);
        }
    }
}

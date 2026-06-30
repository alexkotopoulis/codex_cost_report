package com.example.codexcost;

import java.nio.file.Path;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.staticcontent.StaticContentService;

public final class Main {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Main() {
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getProperty("server.port", System.getenv().getOrDefault("PORT", "8080")));
        Path sessionsDir = Path.of(System.getProperty("codex.sessions.dir",
                Path.of(System.getProperty("user.home"), ".codex", "sessions").toString()));
        SessionRepository repository = new SessionRepository(sessionsDir, MAPPER);

        WebServer server = WebServer.builder()
                .port(port)
                .routing(routing -> routing
                        .get("/api/overview", (req, res) -> sendJson(res, repository.overview()))
                        .get("/api/sessions/{id}", (req, res) -> sendSession(req, res, repository))
                        .register("/", StaticContentService.builder("web").welcomeFileName("index.html").build()))
                .build()
                .start();

        System.out.printf("Codex cost report running at http://localhost:%d/%n", server.port());
        System.out.printf("Reading sessions from %s%n", sessionsDir);
    }

    private static void sendSession(ServerRequest request, ServerResponse response, SessionRepository repository) {
        String id = request.path().pathParameters().get("id");
        repository.session(id)
                .ifPresentOrElse(session -> sendJson(response, session), () -> {
                    response.status(Status.NOT_FOUND_404);
                    sendJson(response, new ErrorResponse("Session not found: " + id));
                });
    }

    private static double creditCost(ServerRequest request) {
        String raw = request.query().first("creditCost").orElse(null);
        if (raw == null || raw.isBlank()) {
            return Pricing.DEFAULT_CREDIT_COST;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Pricing.DEFAULT_CREDIT_COST;
        }
    }

    private static void sendJson(ServerResponse response, Object payload) {
        try {
            response.header(HeaderNames.CONTENT_TYPE, "application/json");
            response.send(MAPPER.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write JSON response", e);
        }
    }

    record ErrorResponse(String message) {
    }
}

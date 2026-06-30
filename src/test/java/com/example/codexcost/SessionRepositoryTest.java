package com.example.codexcost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void readsUsageAndPrompts() throws Exception {
        Path session = tempDir.resolve("2026/06/30/rollout-2026-06-30T12-11-39-abc.jsonl");
        Files.createDirectories(session.getParent());
        Files.writeString(session, """
                {"timestamp":"2026-06-30T19:11:39Z","type":"session_meta","payload":{"id":"abc","timestamp":"2026-06-30T19:11:39Z","cwd":"/tmp/work","source":"vscode"}}
                {"timestamp":"2026-06-30T19:12:00Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"<environment_context>ignored</environment_context>"}]}}
                {"timestamp":"2026-06-30T19:12:01Z","type":"event_msg","payload":{"type":"user_message","message":"build the thing"}}
                {"timestamp":"2026-06-30T19:12:02Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":10,"cached_input_tokens":2,"output_tokens":3,"reasoning_output_tokens":1,"total_tokens":13}}}}
                {"timestamp":"2026-06-30T19:12:03Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":7,"cached_input_tokens":0,"output_tokens":5,"reasoning_output_tokens":2,"total_tokens":12}}}}
                """);

        SessionRepository repository = new SessionRepository(tempDir, new ObjectMapper().registerModule(new JavaTimeModule()));
        Overview overview = repository.overview();
        SessionDetail detail = repository.session("abc").orElseThrow();

        assertEquals(1, overview.sessionCount());
        assertEquals(25, overview.totalUsage().totalTokens());
        assertEquals(17, detail.usage().inputTokens());
        assertEquals(8, detail.usage().outputTokens());
        assertEquals(2, detail.tokenEvents());
        assertEquals("/tmp/work", detail.cwd());
        assertEquals("build the thing", detail.prompts().getFirst());
        assertTrue(overview.days().stream().anyMatch(bucket -> bucket.key().equals("2026-06-30")));
    }
}

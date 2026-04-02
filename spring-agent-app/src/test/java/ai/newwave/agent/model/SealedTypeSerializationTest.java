package ai.newwave.agent.model;

import ai.newwave.agent.scheduling.model.SchedulePayload;
import ai.newwave.agent.timeline.model.TimelineActor;
import ai.newwave.agent.util.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SealedTypeSerializationTest {

    @Test
    void timelineActorSystemRoundtrip() throws Exception {
        TimelineActor actor = new TimelineActor.System("agent");
        String json = Json.MAPPER.writeValueAsString(actor);
        assertTrue(json.contains("\"type\":\"system\""), "Missing type discriminator: " + json);

        TimelineActor deserialized = Json.MAPPER.readValue(json, TimelineActor.class);
        assertInstanceOf(TimelineActor.System.class, deserialized);
        assertEquals("agent", ((TimelineActor.System) deserialized).component());
    }

    @Test
    void timelineActorUserRoundtrip() throws Exception {
        TimelineActor actor = new TimelineActor.User("u-1", "John");
        String json = Json.MAPPER.writeValueAsString(actor);
        assertTrue(json.contains("\"type\":\"user\""), "Missing type discriminator: " + json);

        TimelineActor deserialized = Json.MAPPER.readValue(json, TimelineActor.class);
        assertInstanceOf(TimelineActor.User.class, deserialized);
        assertEquals("John", ((TimelineActor.User) deserialized).displayName());
    }

    @Test
    void timelineActorAgentRoundtrip() throws Exception {
        TimelineActor actor = new TimelineActor.AgentActor("a-1", "assistant");
        String json = Json.MAPPER.writeValueAsString(actor);
        assertTrue(json.contains("\"type\":\"agent\""), "Missing type discriminator: " + json);

        TimelineActor deserialized = Json.MAPPER.readValue(json, TimelineActor.class);
        assertInstanceOf(TimelineActor.AgentActor.class, deserialized);
        assertEquals("a-1", ((TimelineActor.AgentActor) deserialized).agentId());
    }

    @Test
    void schedulePayloadPromptRoundtrip() throws Exception {
        SchedulePayload payload = new SchedulePayload.PromptAction("a-1", "c-1", "hello");
        String json = Json.MAPPER.writeValueAsString(payload);
        assertTrue(json.contains("\"type\":\"prompt\""), "Missing type discriminator: " + json);

        SchedulePayload deserialized = Json.MAPPER.readValue(json, SchedulePayload.class);
        assertInstanceOf(SchedulePayload.PromptAction.class, deserialized);
        assertEquals("hello", ((SchedulePayload.PromptAction) deserialized).message());
    }

    @Test
    void schedulePayloadCustomRoundtrip() throws Exception {
        SchedulePayload payload = new SchedulePayload.CustomAction("webhook", Map.of("url", "https://example.com"));
        String json = Json.MAPPER.writeValueAsString(payload);
        assertTrue(json.contains("\"type\":\"custom\""), "Missing type discriminator: " + json);

        SchedulePayload deserialized = Json.MAPPER.readValue(json, SchedulePayload.class);
        assertInstanceOf(SchedulePayload.CustomAction.class, deserialized);
        assertEquals("webhook", ((SchedulePayload.CustomAction) deserialized).actionType());
    }
}

package ai.newwave.agent.proxy;

import ai.newwave.agent.core.Agent;
import ai.newwave.agent.event.AgentEvent;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * SSE endpoint for streaming agent events.
 * Clients POST a message and receive a stream of AgentEvent SSEs.
 * Opt-in: set agent.proxy.enabled=true to activate.
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(prefix = "agent.proxy", name = "enabled", havingValue = "true")
public class StreamProxyController {

    private final Agent agent;

    public StreamProxyController(Agent agent) {
        this.agent = agent;
    }

    public record StreamRequest(String message) {
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> stream(@RequestBody StreamRequest request) {
        // Subscribe to events before starting the agent
        Flux<ServerSentEvent<AgentEvent>> eventStream = agent.events()
                .map(event -> ServerSentEvent.<AgentEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());

        // Start the agent (non-blocking)
        agent.prompt(request.message()).subscribe();

        // Complete the stream when agent becomes idle
        return eventStream
                .takeUntil(sse -> sse.event() != null && sse.event().equals("agent_end"));
    }
}

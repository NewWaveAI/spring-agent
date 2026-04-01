package ai.newwave.agent.proxy;

import ai.newwave.agent.core.Agent;
import ai.newwave.agent.core.AgentRequest;
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

    public record StreamRequest(String message, String agentId, String conversationId) {
        public StreamRequest {
            if (conversationId == null) conversationId = Agent.DEFAULT_CONVERSATION;
        }
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> stream(@RequestBody StreamRequest request) {
        return agent.stream(AgentRequest.builder()
                        .agentId(request.agentId())
                        .conversationId(request.conversationId())
                        .message(request.message())
                        .build())
                .map(event -> ServerSentEvent.<AgentEvent>builder()
                        .event(event.type().value())
                        .data(event)
                        .build());
    }
}

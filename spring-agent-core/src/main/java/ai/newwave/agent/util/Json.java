package ai.newwave.agent.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared ObjectMapper for the spring-agent library.
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private Json() {}
}

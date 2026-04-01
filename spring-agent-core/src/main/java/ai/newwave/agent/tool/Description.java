package ai.newwave.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a tool parameter field for JSON schema generation.
 * Use on record components to provide descriptions to the LLM.
 *
 * <pre>
 * public record Params(
 *     @Description("The city name, e.g. 'Tokyo'") String location,
 *     @Description("Temperature unit: 'celsius' or 'fahrenheit'") String unit
 * ) {}
 * </pre>
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface Description {
    String value();
}

package ai.newwave.agent.tool;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Test fixtures ---

    record FlatParams(
            @JsonPropertyDescription("A string field") String name,
            @JsonPropertyDescription("An integer field") int count,
            @JsonPropertyDescription("A boolean field") boolean enabled) {}

    record StringListParams(
            @JsonPropertyDescription("List of string tags") List<String> tags) {}

    record Inner(
            @JsonPropertyDescription("Inner string") String value,
            @JsonPropertyDescription("Inner number") int num) {}

    record RecordListParams(
            @JsonPropertyDescription("List of inner objects") List<Inner> items) {}

    record NestedRecordParams(
            @JsonPropertyDescription("Nested object") Inner nested) {}

    record Option(
            @JsonPropertyDescription("Short option label") String label,
            @JsonPropertyDescription("Explanation of what this option means") String description) {}

    record Question(
            @JsonPropertyDescription("Unique ID for this question") String id,
            @JsonPropertyDescription("The question text to display") String question,
            @JsonPropertyDescription("List of option objects") List<Option> options,
            @JsonPropertyDescription("Whether user can select multiple options") boolean multiSelect) {}

    record AskParams(
            @JsonPropertyDescription("List of question objects") List<Question> questions) {}

    // --- Helper ---

    private JsonNode generateSchema(Class<?> type) throws Exception {
        return MAPPER.readTree(JsonSchemaGenerator.generateForType(type));
    }

    // --- Tests ---

    @Test
    void flatRecord_correctJsonTypes() throws Exception {
        JsonNode schema = generateSchema(FlatParams.class);

        assertEquals("object", schema.get("type").asText());
        JsonNode props = schema.get("properties");

        assertEquals("string", props.get("name").get("type").asText());
        assertEquals("A string field", props.get("name").get("description").asText());

        assertEquals("integer", props.get("count").get("type").asText());
        assertEquals("An integer field", props.get("count").get("description").asText());

        assertEquals("boolean", props.get("enabled").get("type").asText());

        JsonNode required = schema.get("required");
        assertTrue(containsValue(required, "name"));
        assertTrue(containsValue(required, "count"));
        assertTrue(containsValue(required, "enabled"));
    }

    @Test
    void listOfString_arrayWithStringItems() throws Exception {
        JsonNode schema = generateSchema(StringListParams.class);

        JsonNode tagsProp = schema.get("properties").get("tags");
        assertEquals("array", tagsProp.get("type").asText());
        assertEquals("string", tagsProp.get("items").get("type").asText());
        assertEquals("List of string tags", tagsProp.get("description").asText());
    }

    @Test
    void listOfRecord_arrayWithObjectItems() throws Exception {
        JsonNode schema = generateSchema(RecordListParams.class);

        JsonNode itemsProp = schema.get("properties").get("items");
        assertEquals("array", itemsProp.get("type").asText());

        JsonNode items = itemsProp.get("items");
        assertEquals("object", items.get("type").asText());
        assertNotNull(items.get("properties"));
        assertEquals("string", items.get("properties").get("value").get("type").asText());
        assertEquals("integer", items.get("properties").get("num").get("type").asText());
    }

    @Test
    void nestedRecord_inlinedAsObject() throws Exception {
        JsonNode schema = generateSchema(NestedRecordParams.class);

        JsonNode nestedProp = schema.get("properties").get("nested");
        assertEquals("object", nestedProp.get("type").asText());
        assertNotNull(nestedProp.get("properties"));
        assertEquals("string", nestedProp.get("properties").get("value").get("type").asText());
        assertEquals("Nested object", nestedProp.get("description").asText());
    }

    @Test
    void deeplyNested_askUserQuestionShape() throws Exception {
        JsonNode schema = generateSchema(AskParams.class);

        // questions: array
        JsonNode questionsProp = schema.get("properties").get("questions");
        assertEquals("array", questionsProp.get("type").asText());
        assertEquals("List of question objects", questionsProp.get("description").asText());

        // questions[]: object with id, question, options, multiSelect
        JsonNode questionItems = questionsProp.get("items");
        assertEquals("object", questionItems.get("type").asText());
        JsonNode qProps = questionItems.get("properties");
        assertEquals("string", qProps.get("id").get("type").asText());
        assertEquals("boolean", qProps.get("multiSelect").get("type").asText());

        // questions[].options: array
        JsonNode optionsProp = qProps.get("options");
        assertEquals("array", optionsProp.get("type").asText());

        // questions[].options[]: object with label, description
        JsonNode optionItems = optionsProp.get("items");
        assertEquals("object", optionItems.get("type").asText());
        JsonNode oProps = optionItems.get("properties");
        assertEquals("string", oProps.get("label").get("type").asText());
        assertEquals("Short option label", oProps.get("label").get("description").asText());
        assertEquals("string", oProps.get("description").get("type").asText());
    }

    @Test
    void descriptions_preservedAtAllLevels() throws Exception {
        JsonNode schema = generateSchema(AskParams.class);

        JsonNode questionsProp = schema.get("properties").get("questions");
        assertEquals("List of question objects", questionsProp.get("description").asText());

        JsonNode qProps = questionsProp.get("items").get("properties");
        assertEquals("Unique ID for this question", qProps.get("id").get("description").asText());
        assertEquals("List of option objects", qProps.get("options").get("description").asText());

        JsonNode oProps = qProps.get("options").get("items").get("properties");
        assertEquals("Short option label", oProps.get("label").get("description").asText());
        assertEquals("Explanation of what this option means", oProps.get("description").get("description").asText());
    }

    private boolean containsValue(JsonNode arrayNode, String value) {
        for (JsonNode node : arrayNode) {
            if (node.asText().equals(value)) return true;
        }
        return false;
    }
}

package com.researchdesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(classes = {Contracts.DocumentState.class, Contracts.TaskState.class, Contracts.RunState.class, Contracts.RunKind.class,
                           Contracts.Availability.class, Contracts.EventType.class})
    void unknownEnumsAreRejected(Class<?> type) {
        assertThatThrownBy(() -> mapper.readValue("\"UNKNOWN\"", type))
            .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
    }

    @Test void sharedFixturesRoundTripWithoutRenamingFields() throws Exception {
        var fixtures = mapper.readTree(Path.of("../../contracts/examples.json").toFile());
        for (var entry : fixtures.properties()) {
            var type = Class.forName("com.researchdesk.Contracts$" + entry.getKey());
            for (var fixture : entry.getValue()) {
                var dto = mapper.treeToValue(fixture, type);
                var serialized = mapper.readTree(mapper.writeValueAsString(dto));
                assertThat(serialized).isEqualTo(fixture);
            }
        }
    }
}

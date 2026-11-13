package it.tesi.cultural;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.parser.OpenAPIV3Parser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpenApiTest {

    @Test
    void specificationParsesAndReferencesResolve() {
        var result = new OpenAPIV3Parser().readLocation(
                Path.of("..", "openapi.yaml").toAbsolutePath().toString(), null, null);
        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getOpenAPI().getPaths()).containsKeys("/users/me", "/feedbacks", "/admin/stats");
        assertThat(result.getOpenAPI().getComponents().getSchemas().get("AuthResponse").getProperties())
                .containsKeys("token", "tokenType", "expiresIn");
        for (String entity : java.util.List.of("User", "CatalogItem", "Event", "Reservation", "Feedback")) {
            Object id = result.getOpenAPI().getComponents().getSchemas().get(entity).getProperties().get("id");
            assertThat(id).isInstanceOfSatisfying(io.swagger.v3.oas.models.media.Schema.class, schema -> {
                assertThat(schema.getType()).as(entity + " ID type").isEqualTo("integer");
                assertThat(schema.getFormat()).as(entity + " ID format").isEqualTo("int32");
            });
        }
    }
}

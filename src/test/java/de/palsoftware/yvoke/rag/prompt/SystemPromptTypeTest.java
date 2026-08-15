package de.palsoftware.yvoke.rag.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The two halves of this enum decide what every stored prompt type MEANS, and they had drifted
 * apart: {@link SystemPromptType#fromString} accepts almost any spelling, while
 * {@link SystemPromptType#dbValue} produced exactly one — lower case — which
 * {@code SystemPromptRepository.findByType} then compared with a case-sensitive {@code =}. The
 * corpus importer writes upper case, so every imported prompt was invisible and the admin ingest
 * page reported "No system prompts of type SUMMARIZE are configured" with one plainly present.
 *
 * <p>
 * Neither half had a unit test. {@code dbValue()} is now the canonical UPPER CASE spelling, and the
 * round trip below is what stops the two drifting again; {@code SystemPromptRepositoryIT} pins the
 * query itself against real PostgreSQL, which is the only place case sensitivity is observable.
 */
public class SystemPromptTypeTest {

    /** The stored spelling, which the corpus export files and importer also use. */
    @Test
    public void dbValueIsTheCanonicalUpperCaseSpelling() {
        assertThat(SystemPromptType.KG.dbValue()).isEqualTo("KG");
        assertThat(SystemPromptType.SUMMARIZE.dbValue()).isEqualTo("SUMMARIZE");
        assertThat(SystemPromptType.CHAT.dbValue()).isEqualTo("CHAT");
    }

    /**
     * write → read → parse must return the value written, for every constant. A lossy round trip is
     * how a prompt ends up stored under a type nothing queries for.
     */
    @Test
    public void everyTypeSurvivesAWriteReadRoundTrip() {
        for (SystemPromptType type : SystemPromptType.values()) {
            assertThat(SystemPromptType.fromString(type.dbValue())).as("round trip for %s", type)
                .isEqualTo(type);
        }
    }

    /** Rows written by older builds are lower case; both spellings must still parse. */
    @Test
    public void parsingIsCaseInsensitive() {
        assertThat(SystemPromptType.fromString("summarize")).isEqualTo(SystemPromptType.SUMMARIZE);
        assertThat(SystemPromptType.fromString("SUMMARIZE")).isEqualTo(SystemPromptType.SUMMARIZE);
        assertThat(SystemPromptType.fromString("SuMmArIzE")).isEqualTo(SystemPromptType.SUMMARIZE);
        assertThat(SystemPromptType.fromString(" kg ")).isEqualTo(SystemPromptType.KG);
    }

    /**
     * The alias set is deliberate history: {@code DB}/{@code DATABASE} predate the rename to
     * SUMMARIZE, and {@code agentic} predates CHAT. Losing one silently re-breaks whatever still
     * writes it.
     */
    @Test
    public void theLegacyAliasesStillResolve() {
        assertThat(SystemPromptType.fromString("DB")).isEqualTo(SystemPromptType.SUMMARIZE);
        assertThat(SystemPromptType.fromString("database")).isEqualTo(SystemPromptType.SUMMARIZE);
        assertThat(SystemPromptType.fromString("summary")).isEqualTo(SystemPromptType.SUMMARIZE);
        assertThat(SystemPromptType.fromString("knowledge_graph")).isEqualTo(SystemPromptType.KG);
        assertThat(SystemPromptType.fromString("knowledge-graph")).isEqualTo(SystemPromptType.KG);
        assertThat(SystemPromptType.fromString("agentic")).isEqualTo(SystemPromptType.CHAT);
    }

    /**
     * An unknown type must THROW rather than default to one, so a typo cannot silently file a
     * prompt under the wrong kind.
     * {@code RagAdminWriteIT.anUnknownPromptTypeIsRejectedRatherThanStored} pins the resulting 4xx
     * at the controller.
     */
    @Test
    public void anUnknownTypeIsRejected() {
        assertThatThrownBy(() -> SystemPromptType.fromString("NOT_A_TYPE"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOT_A_TYPE");
    }

    /** Null is the absence of a type, not an error — callers map it themselves. */
    @Test
    public void nullParsesToNull() {
        assertThat(SystemPromptType.fromString(null)).isNull();
    }
}

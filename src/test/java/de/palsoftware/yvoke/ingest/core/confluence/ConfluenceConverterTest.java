package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.model.Section;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConfluenceConverterTest {

    private static final ConfluenceInstance INSTANCE = new ConfluenceInstance(UUID.randomUUID(),
        "Docs", "docs", "https://wiki.example.com/wiki", "svc@example.com", "tok", null, "SPACE",
        "123", null, null, "collection", "v1", true, true, null, null);

    private ConfluenceClientService confluenceClient;
    private ConfluenceConverter converter;

    @BeforeEach
    void setUp() {
        confluenceClient = Mockito.mock(ConfluenceClientService.class);
        converter = new ConfluenceConverter(confluenceClient);
    }

    /** Attachment processing comes from the JOB's snapshot, so tests spell it out per call. */
    private String convert(String pageId, String xhtml) {
        return converter.convertToMarkdown(INSTANCE, false, pageId, xhtml);
    }

    @Test
    void testBasicXhtmlToMarkdown() {
        String xhtml = "<h1>Hello World</h1><p>This is a <strong>test</strong>.</p>";
        String markdown = convert("123", xhtml);
        // ATX, not setext: the whole chunking pipeline recognises "# " and is blind to "======".
        assertThat(markdown).contains("# Hello World");
        assertThat(markdown).doesNotContain("=====");
        assertThat(markdown).contains("This is a **test**.");
    }

    /**
     * flexmark renders H1/H2 as SETEXT underlines by default ({@code Overview\n========}), and both
     * {@code MarkdownTree} and {@code ConfluenceSectionBuilder} only match ATX ({@code ^#{1,6} }).
     * A normal Confluence page — structured with H1/H2 and no H3 — therefore parsed as a document
     * with NO headings at all: one title blob sliced at arbitrary blank lines, every chunk carrying
     * an empty heading_path.
     */
    @Test
    void headingsAreEmittedAsAtxSoTheChunkerCanSeeThem() {
        String xhtml = "<h1>Overview</h1><p>intro</p>"
            + "<h2>Installation</h2><p>body</p><h3>Deep</h3><p>x</p>";

        String markdown = convert("123", xhtml);

        assertThat(markdown).contains("# Overview");
        assertThat(markdown).contains("## Installation");
        assertThat(markdown).contains("### Deep");
        assertThat(markdown).doesNotContain("=====").doesNotContain("-----");
    }

    @Test
    void allSixHeadingLevelsRoundTripAsAtx() {
        String xhtml = "<h1>One</h1><h2>Two</h2><h3>Three</h3><h4>Four</h4><h5>Five</h5>"
            + "<h6>Six</h6><p>tail</p>";

        String markdown = convert("123", xhtml);

        assertThat(markdown).contains("# One").contains("## Two").contains("### Three")
            .contains("#### Four").contains("##### Five").contains("###### Six");
    }

    /**
     * The whole point of the wave, end to end: a realistic Confluence page (H1/H2/H3, no ATX in the
     * source) must reach the chunker as a nested tree, not as one blob with an empty heading path.
     */
    @Test
    void realisticPageConvertsAndChunksIntoNestedSections() {
        String xhtml =
            "<p>This page describes the agent.</p>" + "<h1>Overview</h1><p>What it is.</p>"
                + "<h2>Installation</h2><p>Run the installer.</p>"
                + "<h3>Prerequisites</h3><p>JDK 25.</p>"
                + "<h2>Configuration</h2><p>Edit application.yml.</p>";

        List<Section> sections =
            ConfluenceSectionBuilder.build("Agent Guide", convert("123", xhtml));

        assertThat(sections).extracting(Section::depth, Section::title, Section::headingPath)
            .containsExactly(tuple(1, "Agent Guide", List.of()),
                tuple(1, "Overview", List.of("Agent Guide")),
                tuple(2, "Installation", List.of("Agent Guide", "Overview")),
                tuple(3, "Prerequisites", List.of("Agent Guide", "Overview", "Installation")),
                tuple(2, "Configuration", List.of("Agent Guide", "Overview")));
        assertThat(sections.get(3).toChunkText())
            .isEqualTo("> Section path: Agent Guide > Overview > Installation\n\n"
                + "### Prerequisites\n\nJDK 25.\n");
    }

    @Test
    void testConfluenceMacros() {
        String xhtml =
            """
                    <ac:structured-macro ac:name="info">
                        <ac:rich-text-body>
                            <p>This is an info panel.</p>
                        </ac:rich-text-body>
                    </ac:structured-macro>
                    <ac:structured-macro ac:name="code">
                        <ac:plain-text-body><![CDATA[System.out.println("hello");]]></ac:plain-text-body>
                    </ac:structured-macro>
                """;

        String markdown = convert("123", xhtml);
        assertThat(markdown).contains("> This is an info panel.");
        assertThat(markdown).contains("System.out.println(\"hello\");");
    }

    @Test
    void testUserTags() {
        when(confluenceClient.getUserDisplayName(INSTANCE, "user123")).thenReturn("John Doe");

        String xhtml = "<p>Hello <ri:user ri:account-id=\"user123\"/></p>";
        String markdown = convert("123", xhtml);

        assertThat(markdown).contains("Hello **@John Doe**");
    }

    @Test
    void testLinks() {
        String xhtml = "<ac:link><ac:link-body>My Link Text</ac:link-body></ac:link>";
        String markdown = convert("123", xhtml);

        assertThat(markdown).contains("[My Link Text](#)");
    }

    /**
     * Mentions must be resolved against the site the page came from: the same Atlassian account id
     * on two connected instances is two different people, so a converter that resolved names
     * through "the" client would print one site's colleague on another site's page.
     */
    @Test
    void userMentionsAreResolvedAgainstTheInstanceThePageCameFrom() {
        ConfluenceInstance other = new ConfluenceInstance(UUID.randomUUID(), "Other", "other",
            "https://other.example.com/wiki", "svc@other.example.com", "tok", null, "OTHER", "9",
            null, null, "collection", null, false, true, null, null);
        when(confluenceClient.getUserDisplayName(INSTANCE, "user123")).thenReturn("John Doe");
        when(confluenceClient.getUserDisplayName(other, "user123")).thenReturn("Jane Roe");

        String xhtml = "<p>Hello <ri:user ri:account-id=\"user123\"/></p>";

        assertThat(converter.convertToMarkdown(other, false, "123", xhtml))
            .contains("Hello **@Jane Roe**");
        assertThat(converter.convertToMarkdown(INSTANCE, false, "123", xhtml))
            .contains("Hello **@John Doe**");
    }

    @Test
    void attachmentsAreFetchedFromTheInstanceTheJobNames() {
        when(confluenceClient.getPageAttachments(INSTANCE, "123")).thenReturn(List.of(
            Map.of("title", "test.txt", "metadata", Map.of("mediaType", "text/plain"), "_links",
                Map.of("download", "/download/test.txt")),
            Map.of("title", "video.mp4", "metadata", Map.of("mediaType", "video/mp4"))));
        when(confluenceClient.downloadAttachment(INSTANCE, "/download/test.txt"))
            .thenReturn("Attachment content from Tika!".getBytes(StandardCharsets.UTF_8));

        String markdown =
            converter.convertToMarkdown(INSTANCE, true, "123", "<p>Some page content.</p>");

        assertThat(markdown).contains("Some page content.").contains("## Attachments")
            .contains("### Attachment: test.txt").contains("Attachment content from Tika!")
            .contains("*Skipped multimedia attachment: video.mp4*");
    }

    /**
     * The flag comes from the crawl-time snapshot in the job, not from the instance row: flipping
     * "process attachments" while a queue drains must not change what a queued page produces.
     */
    @Test
    void attachmentsAreNotTouchedWhenTheJobSnapshotSaysNotTo() {
        assertThat(INSTANCE.processAttachments()).isTrue();

        converter.convertToMarkdown(INSTANCE, false, "123", "<p>Some page content.</p>");

        verify(confluenceClient, never()).getPageAttachments(any(), anyString());
        verify(confluenceClient, never()).downloadAttachment(any(), anyString());
    }
}

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

        assertThat(sections).hasSize(1);
        Section chunk = sections.get(0);
        assertThat(chunk.depth()).isEqualTo(1);
        assertThat(chunk.title()).isEqualTo("Agent Guide");
        assertThat(chunk.headingPath()).isEmpty();
        assertThat(chunk.body()).contains("This page describes the agent.")
            .contains("# Overview\n\nWhat it is.").contains("## Installation\n\nRun the installer.")
            .contains("### Prerequisites\n\nJDK 25.")
            .contains("## Configuration\n\nEdit application.yml.");
        assertThat(chunk.toChunkText()).isEqualTo("""
            # Agent Guide

            This page describes the agent.

            # Overview

            What it is.

            ## Installation

            Run the installer.

            ### Prerequisites

            JDK 25.

            ## Configuration

            Edit application.yml.
            """);
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
     * The 50 MiB guard has to fire BEFORE the download, not after it. The listing already carries
     * {@code extensions.fileSize}, so the size is known for free; deciding after the fetch means
     * every oversized attachment on a wiki — VM images, full product ISOs, database exports, all of
     * which people do attach to Confluence pages — is pulled over the network in full and held in a
     * single {@code byte[]} on the ingest worker before being discarded. That is an OOM on a shared
     * JVM running {@code app.worker.concurrency} pages at once, and the crawl dies on a page whose
     * TEXT was perfectly ingestable; it is also minutes of transfer per page against an instance
     * that is already rate-limiting the crawl.
     *
     * <p>
     * The second attachment is the half that stops the guard being "fixed" into a bail-out: the
     * skip is a {@code continue}, so an oversized file must cost only itself and the rest of the
     * page's attachments must still convert. A {@code break} or an early return would silently
     * truncate the attachment section of every page whose big file happens to be listed first, and
     * the page would still look complete. No existing test sets {@code extensions} at all, so
     * {@code fileSize} has always defaulted to 0 and this branch has never executed.
     */
    @Test
    void anAttachmentOverTheSizeLimitIsSkippedWithoutBeingDownloaded() {
        when(confluenceClient.getPageAttachments(INSTANCE, "123")).thenReturn(List.of(
            Map.of("title", "huge.pdf", "metadata", Map.of("mediaType", "application/pdf"),
                "extensions", Map.of("fileSize", 60L * 1024 * 1024), "_links",
                Map.of("download", "/download/huge.pdf")),
            Map.of("title", "notes.txt", "metadata", Map.of("mediaType", "text/plain"),
                "extensions", Map.of("fileSize", 27L), "_links",
                Map.of("download", "/download/notes.txt"))));
        when(confluenceClient.downloadAttachment(INSTANCE, "/download/notes.txt"))
            .thenReturn("Release notes for build 42.".getBytes(StandardCharsets.UTF_8));

        String markdown =
            converter.convertToMarkdown(INSTANCE, true, "123", "<p>Some page content.</p>");

        verify(confluenceClient, never()).downloadAttachment(INSTANCE, "/download/huge.pdf");
        assertThat(markdown).doesNotContain("huge.pdf");
        // A `continue`, not a bail-out: the rest of the listing still converts.
        assertThat(markdown).contains("### Attachment: notes.txt")
            .contains("Release notes for build 42.");
    }

    /**
     * A Tika extraction failure is a property of the FILE, not of the transport, so it must be
     * recorded and stepped over — while listing and download failures propagate and fail the job.
     * That asymmetry is the whole design of this block: a throttled or broken crawl must not
     * produce pages that look complete with every attachment's text silently missing, but one
     * corrupt or password-protected PDF among a page's attachments must not destroy an otherwise
     * perfectly good page, and must not kill the wider crawl that is retrying 429s around it. Real
     * corpora are full of these — truncated uploads, encrypted PDFs, files whose extension lies
     * about their type.
     *
     * <p>
     * The note matters as much as the survival. Writing the failure INTO the markdown is what makes
     * the gap visible in the corpus itself: an agent (and a human reading the document) sees that
     * this page had an attachment whose text could not be read, instead of silently concluding the
     * attachment did not exist. A log line would not survive into the ingested document, and
     * nothing downstream reconciles attachment counts against extracted sections.
     */
    @Test
    void aFailingTextExtractionLeavesANoteInsteadOfFailingThePage() {
        when(confluenceClient.getPageAttachments(INSTANCE, "123")).thenReturn(List
            .of(Map.of("title", "broken.pdf", "metadata", Map.of("mediaType", "application/pdf"),
                "_links", Map.of("download", "/download/broken.pdf"))));
        // A PDF magic header with nothing behind it: Tika detects application/pdf and the parser
        // throws, which is exactly the shape of a truncated upload in a live space.
        when(confluenceClient.downloadAttachment(INSTANCE, "/download/broken.pdf"))
            .thenReturn("%PDF-1.4\nthis file is truncated".getBytes(StandardCharsets.UTF_8));

        String markdown =
            converter.convertToMarkdown(INSTANCE, true, "123", "<p>Some page content.</p>");

        assertThat(markdown).contains("Some page content.").contains("## Attachments")
            .contains("*Failed to extract text from attachment: broken.pdf*")
            .doesNotContain("### Attachment: broken.pdf");
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

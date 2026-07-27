package de.palsoftware.yvoke.ingest.core.confluence;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.tika.Tika;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConfluenceConverter {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceConverter.class);

    private static final int MAX_EXTRACTED_CHARS = 100_000;

    private static final long MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024;

    private final ConfluenceClientService confluenceClient;
    private final Tika tika;
    private final FlexmarkHtmlConverter htmlConverter;

    public ConfluenceConverter(ConfluenceClientService confluenceClient) {
        this.confluenceClient = confluenceClient;
        this.tika = new Tika();
        this.tika.setMaxStringLength(MAX_EXTRACTED_CHARS);
        // SETEXT_HEADINGS defaults to TRUE, which renders H1/H2 as underlines ("Overview\n=====")
        // and only H3-H6 as ATX. Every downstream heading parser in this codebase
        // (MarkdownTree.HEADING_RE, ConfluenceSectionBuilder) matches ATX only, so a normal
        // Confluence page structured with H1/H2 parsed as a document with NO headings: one blob
        // sliced at arbitrary blank lines, every chunk carrying an empty heading_path. Forcing ATX
        // is the single point where the whole page structure becomes visible to the chunker.
        MutableDataSet options = new MutableDataSet();
        options.set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false);
        this.htmlConverter = FlexmarkHtmlConverter.builder(options).build();
    }

    /**
     * Converts one page's storage-format XHTML to markdown.
     *
     * <p>
     * The instance is passed in rather than looked up: mentions and attachments are resolved with
     * live calls, and they must go to the site the page came from. {@code processAttachments} comes
     * from the JOB's snapshot of the connector settings, not from the instance's current row, so a
     * setting flipped mid-drain cannot change what a queued page produces.
     */
    @SuppressWarnings("unchecked")
    public String convertToMarkdown(ConfluenceInstance instance, boolean processAttachments,
        String pageId, String xhtmlBody) {
        // 1. Pre-process XHTML with JSoup
        Document doc = Jsoup.parseBodyFragment(xhtmlBody);

        // a) Extract plain text from links
        Elements links = doc.select("ac|link");
        for (Element link : links) {
            Element body = link.selectFirst("ac|link-body");
            if (body != null) {
                link.replaceWith(new Element("a").text(body.text()).attr("href", "#"));
            }
        }

        // b) Handle macros like code blocks, panels, etc
        Elements macros = doc.select("ac|structured-macro");
        for (Element macro : macros) {
            String name = macro.attr("ac:name");
            if ("code".equals(name)) {
                Element plainTextBody = macro.selectFirst("ac|plain-text-body");
                if (plainTextBody != null) {
                    Element pre = new Element("pre");
                    Element code = new Element("code");
                    code.text(plainTextBody.text());
                    pre.appendChild(code);
                    macro.replaceWith(pre);
                }
            } else if ("info".equals(name) || "note".equals(name) || "warning".equals(name)
                || "panel".equals(name)) {
                Element richTextBody = macro.selectFirst("ac|rich-text-body");
                if (richTextBody != null) {
                    Element blockquote = new Element("blockquote");
                    blockquote.html(richTextBody.html());
                    macro.replaceWith(blockquote);
                }
            }
        }

        // c) Handle user tags
        Elements users = doc.select("ri|user");
        for (Element user : users) {
            String accountId = user.attr("ri:account-id");
            if (accountId != null && !accountId.isBlank()) {
                String displayName = confluenceClient.getUserDisplayName(instance, accountId);
                user.replaceWith(new Element("strong").text("@" + displayName));
            }
        }

        // d) Handle images
        Elements images = doc.select("ac|image");
        for (Element image : images) {
            Element attachment = image.selectFirst("ri|attachment");
            if (attachment != null) {
                String filename = attachment.attr("ri:filename");
                image.replaceWith(new Element("p").text("[Image: " + filename + "]"));
            }
        }

        // 2. Convert to Markdown
        String markdown = htmlConverter.convert(doc.body().html());

        // 3. Process and append attachments
        if (processAttachments) {
            StringBuilder attachmentsMd = new StringBuilder();
            // Listing and downloading now PROPAGATE (they are retried for 429 inside the client):
            // swallowing them meant a throttled crawl produced pages that looked complete but had
            // every attachment's text missing. Only a Tika *extraction* failure stays non-fatal —
            // that is a property of the file, not of the transport, and it is recorded in the
            // corpus so it is visible rather than silent.
            List<Map<String, Object>> attachments =
                confluenceClient.getPageAttachments(instance, pageId);
            for (Map<String, Object> attachment : attachments) {
                String title = (String) attachment.get("title");
                Map<String, Object> metadata = (Map<String, Object>) attachment.get("metadata");
                Map<String, Object> extensions = (Map<String, Object>) attachment.get("extensions");

                String mediaType = metadata != null && metadata.get("mediaType") != null
                    ? (String) metadata.get("mediaType")
                    : "";

                long fileSize = 0;
                if (extensions != null && extensions.get("fileSize") != null) {
                    fileSize = ((Number) extensions.get("fileSize")).longValue();
                }

                if (fileSize > MAX_ATTACHMENT_BYTES) {
                    log.info(
                        "Skipping attachment '{}' because it exceeds the 50MB size limit ({} bytes)",
                        title, fileSize);
                    continue;
                }

                if (mediaType != null) {
                    if (mediaType.startsWith("video/") || mediaType.startsWith("audio/")) {
                        attachmentsMd.append("\n\n*Skipped multimedia attachment: ").append(title)
                            .append("*\n");
                        continue;
                    }
                    if (mediaType.startsWith("image/")) {
                        continue;
                    }
                    if (mediaType.equals("application/gliffy+json")
                        || mediaType.equals("application/zip")
                        || mediaType.equals("application/x-tar")) {
                        attachmentsMd.append("\n\n*Skipped unsupported binary: ").append(title)
                            .append("*\n");
                        continue;
                    }
                }

                Map<String, Object> linksMap = (Map<String, Object>) attachment.get("_links");
                if (linksMap != null && linksMap.get("download") != null) {
                    String downloadUrl = (String) linksMap.get("download");
                    byte[] fileData = confluenceClient.downloadAttachment(instance, downloadUrl);
                    try (InputStream is = new ByteArrayInputStream(fileData)) {
                        String extractedText = tika.parseToString(is);
                        if (extractedText != null && !extractedText.trim().isEmpty()) {
                            attachmentsMd.append("\n\n### Attachment: ").append(title)
                                .append("\n\n");
                            attachmentsMd.append(extractedText.trim());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to extract text from attachment: {}", title, e);
                        attachmentsMd.append("\n\n*Failed to extract text from attachment: ")
                            .append(title).append("*\n");
                    }
                }
            }

            if (attachmentsMd.length() > 0) {
                markdown += "\n\n## Attachments\n" + attachmentsMd;
            }
        }

        return markdown;
    }
}

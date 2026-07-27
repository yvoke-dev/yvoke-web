package de.palsoftware.yvoke.mcp.tools;

import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier;
import java.util.*;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class VerifyCitationsTool {

    private static final Logger log = LoggerFactory.getLogger(VerifyCitationsTool.class);

    /**
     * What the model reads before deciding how much the verdict is worth. The tool resolves ids
     * against the id columns and never loads chunk text, so it cannot describe itself as a
     * faithfulness or hallucination check without over-claiming.
     */
    public static final String DESCRIPTION =
        "Check that cited ids are real: confirms each chunk/document id EXISTS in the corpus. "
            + "This is an existence check only — it does not read the cited text, so it does NOT "
            + "confirm that the source supports the claim it is attached to (a real id attached to "
            + "an invented or mistranslated paraphrase still passes). Accepts citation tokens from "
            + "'search_corpus' results (chunk ids, document ids) and numbered reference markers "
            + "like [1]. Returns a markdown table with columns: citation, kind, status, detail. "
            + "FABRICATED = the id does not exist and must be removed or corrected. UNVERIFIED = "
            + "the id was not checked in the form given; re-send it in full.";

    /** Kept in one place: the old two examples advertised a form the tool then condemned. */
    public static final String CITATIONS_PARAM_DESCRIPTION =
        "List of citation tokens to verify, e.g. "
            + "\"[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]\", "
            + "\"[document_id=e3b0c442-98fc-1c14-9afb-f4c8996fb924]\", a bare full chunk uuid, or "
            + "\"[1]\". Ids must be complete: a truncated 8-character id cannot be checked.";

    /** Appended to every report, so the scope of the verdict travels with the verdict. */
    public static final String SCOPE_NOTE =
        "_Scope: this checks that each cited id exists in the corpus. It does not read the cited "
            + "text, so it does NOT confirm that the source supports the claim it is attached to._";

    private final CitationVerifier citationVerifier;

    public VerifyCitationsTool(CitationVerifier citationVerifier) {
        this.citationVerifier = citationVerifier;
    }

  @McpTool(name = "verify_citations", description = DESCRIPTION)
  @Tool(name = "verify_citations", description = DESCRIPTION)
  public String verifyCitations(
      @McpToolParam(description = CITATIONS_PARAM_DESCRIPTION, required = true)
          @ToolParam(description = CITATIONS_PARAM_DESCRIPTION, required = true)
          List<String> citations) {
    log.info("VerifyCitationsTool: verifying {} citations", citations != null ? citations.size() : 0);
    if (citations == null || citations.isEmpty()) {
      return "Error: 'citations' parameter is required. At least one citation token must be specified.";
    }
    try {
      List<CitationVerifier.CitationCheckResult> checkResults =
          citationVerifier.checkCitations(citations);
      List<Map<String, String>> rows = new ArrayList<>();
      int n_fab = 0;
      int n_unverified = 0;
      for (CitationVerifier.CitationCheckResult res : checkResults) {
        Map<String, String> row = new HashMap<>();
        row.put("citation", res.getCitation());
        row.put("kind", res.getKind());

        String statusStr =
            switch (res.getStatus()) {
              case REAL -> "REAL";
              case REAL_AMBIG -> "REAL(ambig)";
              case UNVERIFIED -> "UNVERIFIED";
              case FABRICATED -> "FABRICATED";
            };
        if (res.getStatus() == CitationVerifier.CitationStatus.FABRICATED) {
          n_fab++;
        } else if (res.getStatus() == CitationVerifier.CitationStatus.UNVERIFIED) {
          n_unverified++;
        }

        row.put("status", statusStr);
        row.put("detail", res.getDetail());
        rows.add(row);
      }

      List<String> out = new ArrayList<>();
      String header = "# Citation check — " + rows.size() + " cited, " + n_fab + " FABRICATED";
      if (n_unverified > 0) {
        header += ", " + n_unverified + " UNVERIFIED";
      }
      out.add(header);
      out.add(McpToolUtils.formatTableRows(rows, List.of("citation", "kind", "status", "detail")));

      if (n_fab > 0) {
        out.add(
            "\n**Remove or correct every FABRICATED citation before answering.** A fabricated citation is worse than no citation — either find the real source or drop the claim.");
      }
      if (n_unverified > 0) {
        out.add(
            "\n**UNVERIFIED means not checked, not wrong.** A chunk or document id is only checked in its full form (36-character uuid, or 32 hex characters); re-send the full id if you need a verdict. Do not drop the citation on the strength of this status.");
      }
      if (n_fab == 0 && n_unverified == 0) {
        out.add("\nEvery cited id exists in the corpus. ✅");
      }
      out.add("\n" + SCOPE_NOTE);
      return String.join("\n", out);
    } catch (Exception e) {
      return McpToolUtils.toolError("verify_citations", e);
    }
  }
}

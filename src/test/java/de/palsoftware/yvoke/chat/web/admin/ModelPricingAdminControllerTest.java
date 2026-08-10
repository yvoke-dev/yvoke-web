package de.palsoftware.yvoke.chat.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.service.CostCalculationService;
import de.palsoftware.yvoke.chat.core.service.CostCalculationService.UsedModelPricingStatus;
import de.palsoftware.yvoke.shared.user.service.UserService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import de.palsoftware.yvoke.shared.web.MvcExceptionHandler;

@ExtendWith(MockitoExtension.class)
public class ModelPricingAdminControllerTest {

    @Mock
    private CostCalculationService costCalculationService;

    @Mock
    private UserService userService;

    @InjectMocks
    private ModelPricingAdminController controller;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        // Register the shared MVC error advice so exception paths (e.g. an empty upload) resolve
        // exactly as they do in production — flash + redirect-back — instead of propagating raw.
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new MvcExceptionHandler()).build();
    }

    @Test
    public void getModelPricingPage_returnsViewWithStatuses() throws Exception {
        UsedModelPricingStatus configured = new UsedModelPricingStatus("gemini-3.6-flash", true,
            BigDecimal.valueOf(1.5), BigDecimal.valueOf(9.0), BigDecimal.valueOf(0.15),
            BigDecimal.valueOf(9.0), Instant.now(), 42L);
        UsedModelPricingStatus unconfigured =
            new UsedModelPricingStatus("unknown-model", false, null, null, null, null, null, 0L);

        when(costCalculationService.getAllUsedModelsWithPricingStatus())
            .thenReturn(List.of(configured, unconfigured));

        mockMvc.perform(get("/admin/pricing")).andExpect(status().isOk())
            .andExpect(view().name("admin/model-pricing"))
            .andExpect(model().attribute("activeTab", "pricing"))
            .andExpect(model().attribute("totalModelsCount", 2))
            .andExpect(model().attribute("configuredCount", 1L))
            .andExpect(model().attribute("missingCount", 1L))
            .andExpect(model().attribute("usedCount", 1L))
            .andExpect(model().attribute("unusedCount", 1L));
    }

    @Test
    public void exportModelPricing_returnsJsonFile() throws Exception {
        UsedModelPricingStatus configured = new UsedModelPricingStatus("gemini-3.6-flash", true,
            BigDecimal.valueOf(1.5), BigDecimal.valueOf(9.0), BigDecimal.valueOf(0.15),
            BigDecimal.valueOf(9.0), Instant.now(), 42L);
        when(costCalculationService.getAllUsedModelsWithPricingStatus())
            .thenReturn(List.of(configured));

        mockMvc.perform(get("/admin/pricing/export")).andExpect(status().isOk())
            .andExpect(MockMvcResultMatchers.header().string("Content-Disposition",
                "attachment; filename=\"model-pricing-export.json\""))
            .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(MockMvcResultMatchers.content()
                .string(Matchers.containsString("gemini-3.6-flash")));
    }

    /**
     * Export is the input to import, so anything the export emits comes back as a genuine price. A
     * model with no pricing row is not a model priced at zero — it is a model nobody has priced
     * yet, which the admin page deliberately surfaces as a "missing" count so an operator can go
     * and set it. Emitting it anyway would send the zero-filled placeholder columns the mapper
     * substitutes for its null prices, and the next import turns that open item into a
     * deliberate-looking {@code 0.00}: the model disappears from the missing count forever while
     * continuing to bill nothing on every cost report, which is the one direction of pricing error
     * that never produces a complaint. {@code exportModelPricing_returnsJsonFile} supplies only a
     * priced model, so the filter can be deleted without it noticing.
     */
    @Test
    public void exportOmitsModelsThatHaveNoPricingRow() throws Exception {
        UsedModelPricingStatus priced = new UsedModelPricingStatus("gemini-3.6-flash", true,
            BigDecimal.valueOf(1.5), BigDecimal.valueOf(9.0), BigDecimal.valueOf(0.15),
            BigDecimal.valueOf(9.0), Instant.now(), 42L);
        UsedModelPricingStatus unpriced = new UsedModelPricingStatus("never-priced-model", false,
            null, null, null, null, null, 7L);
        when(costCalculationService.getAllUsedModelsWithPricingStatus())
            .thenReturn(List.of(priced, unpriced));

        mockMvc.perform(get("/admin/pricing/export")).andExpect(status().isOk())
            .andExpect(
                MockMvcResultMatchers.content().string(Matchers.containsString("gemini-3.6-flash")))
            .andExpect(MockMvcResultMatchers.content()
                .string(Matchers.not(Matchers.containsString("never-priced-model"))));
    }

    @Test
    public void importModelPricing_success() throws Exception {
        String json = """
            [
              {
                "modelName": "gemini-3.6-flash",
                "promptPricePerMillion": 1.5,
                "completionPricePerMillion": 9.0,
                "cachedPricePerMillion": 0.15,
                "thoughtPricePerMillion": 9.0
              }
            ]
            """;
        MockMultipartFile file = new MockMultipartFile("file", "import.json", "application/json",
            json.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(MockMvcRequestBuilders.multipart("/admin/pricing/import").file(file))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/pricing"))
            .andExpect(MockMvcResultMatchers.flash().attributeExists("success"));

        verify(costCalculationService).updateModelPricing("gemini-3.6-flash",
            BigDecimal.valueOf(1.5), BigDecimal.valueOf(9.0), BigDecimal.valueOf(0.15),
            BigDecimal.valueOf(9.0));
    }

    /**
     * The exporter emits the long field names; hand-authored and vendor-sourced pricing files use
     * the short ones, and a single file can carry both — an export that was later edited by hand,
     * which is exactly how these files get maintained. The tie has to go to the canonical field,
     * because that is the spelling the exporter produced and therefore the one that reflects what
     * is actually in the database; resolving to the alias silently reprices the model from whatever
     * stale number the hand edit left behind. Nothing downstream would catch it: pricing is not
     * validated anywhere, so a wrong rate simply becomes the cost report, and the report is the
     * only place anyone would ever see it.
     *
     * <p>
     * The zero default is the same contract in the other direction — a price the file never
     * mentions must land as an explicit {@code 0.00}, never a null, because
     * {@code updateModelPricing} writes it straight into NOT NULL columns. No existing test reaches
     * any of this: {@code importModelPricing_success} supplies only the canonical spelling, so all
     * four {@code getEffective*} methods return on their first branch and both the alias fallback
     * and the zero default are dead code as far as the suite is concerned.
     */
    @Test
    public void importPrefersTheCanonicalPriceFieldOverTheShortAlias() throws Exception {
        String json = """
            [
              {
                "modelName": "canonical-and-alias",
                "promptPricePerMillion": 1.5,
                "completionPricePerMillion": 2.0,
                "prompt": 9.9,
                "completion": 8.8
              },
              {
                "modelName": "alias-only",
                "prompt": 3.25,
                "completion": 4.5,
                "cached": 0.5,
                "thought": 6.75
              }
            ]
            """;
        MockMultipartFile file = new MockMultipartFile("file", "import.json", "application/json",
            json.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(MockMvcRequestBuilders.multipart("/admin/pricing/import").file(file))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/pricing"));

        ArgumentCaptor<BigDecimal> prompt = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> cached = ArgumentCaptor.forClass(BigDecimal.class);
        verify(costCalculationService).updateModelPricing(eq("canonical-and-alias"),
            prompt.capture(), any(), cached.capture(), any());
        assertThat(prompt.getValue()).isEqualByComparingTo("1.5");
        // Neither spelling present for the cached price: it must arrive as zero, not as null.
        assertThat(cached.getValue()).isEqualByComparingTo("0");

        ArgumentCaptor<BigDecimal> aliasPrompt = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> aliasThought = ArgumentCaptor.forClass(BigDecimal.class);
        verify(costCalculationService).updateModelPricing(eq("alias-only"), aliasPrompt.capture(),
            any(), any(), aliasThought.capture());
        assertThat(aliasPrompt.getValue()).isEqualByComparingTo("3.25");
        assertThat(aliasThought.getValue()).isEqualByComparingTo("6.75");
    }

    @Test
    public void importModelPricing_emptyFile_showsError() throws Exception {
        MockMultipartFile file =
            new MockMultipartFile("file", "empty.json", "application/json", new byte[0]);

        // A browser form post: the unified advice turns the "empty file" rejection into a flash +
        // redirect back to the page that hosted the form (its Referer).
        mockMvc
            .perform(MockMvcRequestBuilders.multipart("/admin/pricing/import").file(file)
                .header("Accept", "text/html").header("Referer", "http://localhost/admin/pricing"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/pricing"))
            .andExpect(MockMvcResultMatchers.flash().attribute("error", "Uploaded file is empty."));
    }

    /**
     * {@code modelName} is the join key, not a label: {@code llm_call_logs.model} stores the raw
     * provider id with no padding, and {@code CostCalculationService} looks pricing up by exact
     * name. A file that came out of a spreadsheet or a copy-paste carries padded names routinely,
     * and an untrimmed import creates a SECOND pricing row that will never match a single logged
     * call, while the model it was meant to price stays unpriced and keeps costing $0 on every cost
     * report — the pricing page then shows the model both as configured and as missing.
     *
     * <p>
     * The skip is the same failure in miniature: a trailing empty object, or one whose name field
     * was blanked during an edit, would otherwise be written under a blank name. And the count fed
     * back to the operator is the ONLY signal they get that the file did what they intended, so it
     * has to count rows actually written rather than rows present in the file — "imported 3
     * model(s)" for a file that priced one is worse than an error, because it ends the operator's
     * investigation.
     */
    @Test
    public void importTrimsTheModelNameAndSkipsBlankEntries() throws Exception {
        String json = """
            [
              {"modelName": "  gemini-3.6-flash  ", "promptPricePerMillion": 1.5},
              {"modelName": "   ", "promptPricePerMillion": 2.5},
              {"promptPricePerMillion": 3.5}
            ]
            """;
        MockMultipartFile file = new MockMultipartFile("file", "import.json", "application/json",
            json.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(MockMvcRequestBuilders.multipart("/admin/pricing/import").file(file))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/pricing"))
            .andExpect(MockMvcResultMatchers.flash().attribute("success",
                Matchers.containsString("1 model(s)")));

        verify(costCalculationService).updateModelPricing(eq("gemini-3.6-flash"), any(), any(),
            any(), any());
        // Exactly one write: neither the blank name nor the missing one reached the database.
        verify(costCalculationService, times(1)).updateModelPricing(any(), any(), any(), any(),
            any());
    }

    @Test
    public void saveModelPricing_savesAndRedirects() throws Exception {
        mockMvc
            .perform(post("/admin/pricing/save").param("modelName", "custom-model")
                .param("prompt", "2.50").param("completion", "10.00").param("cached", "0.20")
                .param("thought", "10.00"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/pricing"));

        verify(costCalculationService).updateModelPricing("custom-model", new BigDecimal("2.50"),
            new BigDecimal("10.00"), new BigDecimal("0.20"), new BigDecimal("10.00"));
    }

    @Test
    public void deleteModelPricing_deletesAndRedirects() throws Exception {
        mockMvc.perform(post("/admin/pricing/delete").param("modelName", "custom-model"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/pricing"));

        verify(costCalculationService).deleteModelPricing("custom-model");
    }
}

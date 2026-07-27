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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
            .setControllerAdvice(new de.palsoftware.yvoke.shared.web.MvcExceptionHandler()).build();
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

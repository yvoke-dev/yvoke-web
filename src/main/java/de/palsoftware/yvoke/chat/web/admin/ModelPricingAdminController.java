package de.palsoftware.yvoke.chat.web.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.service.CostCalculationService;
import de.palsoftware.yvoke.chat.core.service.CostCalculationService.UsedModelPricingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Controller
@RequestMapping("/admin/pricing")
public class ModelPricingAdminController {

    private static final Logger log = LoggerFactory.getLogger(ModelPricingAdminController.class);

    private final CostCalculationService costCalculationService;
    private final ObjectMapper objectMapper;

    public ModelPricingAdminController(CostCalculationService costCalculationService) {
        this.costCalculationService = costCalculationService;
        this.objectMapper = new ObjectMapper();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelPricingExportDto(
        @JsonProperty("modelName") String modelName,
        @JsonProperty("promptPricePerMillion") BigDecimal promptPricePerMillion,
        @JsonProperty("completionPricePerMillion") BigDecimal completionPricePerMillion,
        @JsonProperty("cachedPricePerMillion") BigDecimal cachedPricePerMillion,
        @JsonProperty("thoughtPricePerMillion") BigDecimal thoughtPricePerMillion,
        @JsonProperty("prompt") BigDecimal prompt,
        @JsonProperty("completion") BigDecimal completion,
        @JsonProperty("cached") BigDecimal cached,
        @JsonProperty("thought") BigDecimal thought
    ) {
        public BigDecimal getEffectivePrompt() {
            if (promptPricePerMillion != null) return promptPricePerMillion;
            if (prompt != null) return prompt;
            return BigDecimal.ZERO;
        }

        public BigDecimal getEffectiveCompletion() {
            if (completionPricePerMillion != null) return completionPricePerMillion;
            if (completion != null) return completion;
            return BigDecimal.ZERO;
        }

        public BigDecimal getEffectiveCached() {
            if (cachedPricePerMillion != null) return cachedPricePerMillion;
            if (cached != null) return cached;
            return BigDecimal.ZERO;
        }

        public BigDecimal getEffectiveThought() {
            if (thoughtPricePerMillion != null) return thoughtPricePerMillion;
            if (thought != null) return thought;
            return BigDecimal.ZERO;
        }
    }

    @GetMapping
    public String getModelPricingPage(Model model) {
        log.info("Accessing Model Pricing Admin view");
        model.addAttribute("activeTab", "pricing");

        List<UsedModelPricingStatus> modelsList =
            costCalculationService.getAllUsedModelsWithPricingStatus();
        long configuredCount =
            modelsList.stream().filter(UsedModelPricingStatus::hasPricing).count();
        long missingCount = modelsList.size() - configuredCount;
        long usedCount = modelsList.stream().filter(m -> m.usageCount() > 0).count();
        long unusedCount = modelsList.size() - usedCount;

        model.addAttribute("modelStatuses", modelsList);
        model.addAttribute("totalModelsCount", modelsList.size());
        model.addAttribute("configuredCount", configuredCount);
        model.addAttribute("missingCount", missingCount);
        model.addAttribute("usedCount", usedCount);
        model.addAttribute("unusedCount", unusedCount);
        return "admin/model-pricing";
    }

    @GetMapping("/export")
    public ResponseEntity<Resource> exportModelPricing() {
        log.info("Exporting model pricing configurations");
        List<UsedModelPricingStatus> modelsList =
            costCalculationService.getAllUsedModelsWithPricingStatus();
        List<ModelPricingExportDto> exports = modelsList.stream()
            .filter(UsedModelPricingStatus::hasPricing)
            .map(s -> new ModelPricingExportDto(s.modelName(),
                s.promptPricePerMillion() != null ? s.promptPricePerMillion() : BigDecimal.ZERO,
                s.completionPricePerMillion() != null ? s.completionPricePerMillion()
                    : BigDecimal.ZERO,
                s.cachedPricePerMillion() != null ? s.cachedPricePerMillion() : BigDecimal.ZERO,
                s.thoughtPricePerMillion() != null ? s.thoughtPricePerMillion() : BigDecimal.ZERO,
                null, null, null, null))
            .toList();

        String json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(exports);
        } catch (Exception e) {
            log.error("Failed to serialize model pricing to JSON", e);
            json = "[]";
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(bytes);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"model-pricing-export.json\"")
            .contentType(MediaType.APPLICATION_JSON).contentLength(bytes.length).body(resource);
    }

    @PostMapping("/import")
    public String importModelPricing(@RequestParam("file") MultipartFile file,
        RedirectAttributes redirectAttributes) throws IOException {
        log.info("Importing model pricing from file");
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        List<ModelPricingExportDto> items;
        if (content.trim().startsWith("[")) {
            items = objectMapper.readValue(content,
                new TypeReference<List<ModelPricingExportDto>>() {});
        } else {
            ModelPricingExportDto single =
                objectMapper.readValue(content, ModelPricingExportDto.class);
            items = List.of(single);
        }

        int importedCount = 0;
        for (ModelPricingExportDto item : items) {
            if (item.modelName() != null && !item.modelName().isBlank()) {
                costCalculationService.updateModelPricing(item.modelName().trim(),
                    item.getEffectivePrompt(), item.getEffectiveCompletion(),
                    item.getEffectiveCached(), item.getEffectiveThought());
                importedCount++;
            }
        }
        redirectAttributes.addFlashAttribute("success",
            "Successfully imported pricing for " + importedCount + " model(s).");
        return "redirect:/admin/pricing";
    }

    @PostMapping("/save")
    public String saveModelPricing(@RequestParam String modelName,
        @RequestParam(defaultValue = "0.00") BigDecimal prompt,
        @RequestParam(defaultValue = "0.00") BigDecimal completion,
        @RequestParam(defaultValue = "0.00") BigDecimal cached,
        @RequestParam(defaultValue = "0.00") BigDecimal thought) {
        log.info(
            "Saving model pricing for model '{}': prompt={}, completion={}, cached={}, thought={}",
            modelName, prompt, completion, cached, thought);
        costCalculationService.updateModelPricing(modelName, prompt, completion, cached, thought);
        return "redirect:/admin/pricing";
    }

    @PostMapping("/delete")
    public String deleteModelPricing(@RequestParam String modelName) {
        log.info("Deleting model pricing for model '{}'", modelName);
        costCalculationService.deleteModelPricing(modelName);
        return "redirect:/admin/pricing";
    }
}

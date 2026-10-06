package de.palsoftware.yvoke.area.web.admin;

import de.palsoftware.yvoke.area.core.AreaService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Areas admin page: create, edit, rename and delete areas and pick each one's defaults. A
 * refused save (a taken name, a default that is not a member) is an
 * {@link IllegalArgumentException}, which {@code MvcExceptionHandler} shows as an error flash.
 */
@Controller
@RequestMapping("/admin")
public class AreaAdminController {

    private final AreaService areaService;

    public AreaAdminController(AreaService areaService) {
        this.areaService = areaService;
    }

    @GetMapping("/areas")
    public String viewAreas(Model model) {
        model.addAttribute("areas", areaService.listAreasWithMembers());
        return "admin/areas";
    }

    @PostMapping("/areas")
    public String saveArea(@RequestParam(required = false) String originalName,
        @RequestParam String name, @RequestParam(required = false) String title,
        @RequestParam(required = false) String description,
        @RequestParam(required = false, defaultValue = "false") boolean prototype,
        @RequestParam(required = false) String defaultSystemPrompt,
        @RequestParam(required = false) String defaultPlaybook,
        @RequestParam(required = false) String defaultProfile,
        RedirectAttributes redirectAttributes) {
        areaService.saveArea(originalName, name, title, description, prototype, defaultSystemPrompt,
            defaultPlaybook, defaultProfile);
        redirectAttributes.addFlashAttribute("success", "Area '" + name.trim() + "' saved.");
        return "redirect:/admin/areas";
    }

    @PostMapping("/areas/delete")
    public String deleteArea(@RequestParam String name, RedirectAttributes redirectAttributes) {
        areaService.deleteArea(name);
        redirectAttributes.addFlashAttribute("success", "Area '" + name + "' deleted.");
        return "redirect:/admin/areas";
    }
}

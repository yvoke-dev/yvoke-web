package de.palsoftware.yvoke.shared.security;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    private final boolean mockAuth;
    private final Optional<AvailableAreasProvider> availableAreasProvider;

    @Autowired
    public LoginController(@Value("${app.security.mock}") boolean mockAuth,
        Optional<AvailableAreasProvider> availableAreasProvider) {
        this.mockAuth = mockAuth;
        this.availableAreasProvider = availableAreasProvider;
    }

    // Package-private test-seam constructor
    LoginController(boolean mockAuth, AvailableAreasProvider availableAreasProvider) {
        this(mockAuth, Optional.ofNullable(availableAreasProvider));
    }

    @GetMapping("/login")
    public String login(Model model) {
        if (!mockAuth) {
            return "redirect:/oauth2/authorization/entra";
        }
        List<String> availableAreas =
            availableAreasProvider.map(AvailableAreasProvider::listAreaNames).orElseGet(List::of);
        model.addAttribute("availableAreas", availableAreas != null ? availableAreas : List.of());
        return "login";
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/chat";
    }

    @GetMapping("/logged-out")
    public String loggedOut() {
        return "logged-out";
    }
}

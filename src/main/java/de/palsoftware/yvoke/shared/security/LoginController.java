package de.palsoftware.yvoke.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    private final boolean mockAuth;

    public LoginController(@Value("${app.security.mock}") boolean mockAuth) {
        this.mockAuth = mockAuth;
    }

    @GetMapping("/login")
    public String login(Model model) {
        if (!mockAuth) {
            return "redirect:/oauth2/authorization/entra";
        }
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

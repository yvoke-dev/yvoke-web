package de.palsoftware.yvoke.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ChatEnabledInterceptor implements HandlerInterceptor {

    private final boolean chatEnabled;

    public ChatEnabledInterceptor(@Value("${app.chat.enabled}") boolean chatEnabled) {
        this.chatEnabled = chatEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
        Object handler) throws Exception {
        if (!chatEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Webchat is disabled.");
        }
        return true;
    }
}

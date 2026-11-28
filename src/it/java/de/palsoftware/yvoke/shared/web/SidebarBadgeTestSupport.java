package de.palsoftware.yvoke.shared.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Shared HTML-parsing helpers for sidebar assertions across integration tests. */
public final class SidebarBadgeTestSupport {

    private SidebarBadgeTestSupport() {}

    /**
     * Extracts the visible role text from {@code .user-role-badge} using Jsoup so tests stay
     * decoupled from adjacent HTML comments and layout formatting.
     */
    public static String extractRoleBadgeText(String html) {
        Document document = Jsoup.parse(html);
        Element badge = document.selectFirst(".user-role-badge");
        return badge != null ? badge.text().trim() : "";
    }
}

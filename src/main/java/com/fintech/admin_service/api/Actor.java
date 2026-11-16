package com.fintech.admin_service.api;

import jakarta.servlet.http.HttpServletRequest;

/** The admin making the call (set by AdminAccessFilter). */
final class Actor {

    private Actor() {
    }

    static String id(HttpServletRequest request) {
        Object actor = request.getAttribute(AdminAccessFilter.ACTOR_ATTRIBUTE);
        return actor == null ? "unknown" : actor.toString();
    }
}

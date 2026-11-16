package com.fintech.admin_service.api;

import com.fintech.admin_service.domain.ApiException;
import com.fintech.admin_service.downstream.Downstream;
import com.fintech.admin_service.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/**
 * Email-change and account-deletion queues. Lists are privacy-safe projections (masked e-mails, request ids, states). Actions are
 * performed by user-service as the calling admin and each one is written to the audit log, whatever the outcome.
 */
@RestController
@RequestMapping("/api/admin")
public class RequestsController {

    private static final Set<String> DELETION_ACTIONS = Set.of("process", "complete", "cancel");

    private final Downstream downstream;
    private final AuditService audit;

    public RequestsController(Downstream downstream, AuditService audit) {
        this.downstream = downstream;
        this.audit = audit;
    }

    @GetMapping("/email-change-requests")
    public List<Map<String, Object>> emailChanges() {
        try {
            return Projection.pickAll(downstream.emailChanges(), "id", "userRef", "newEmailMasked", "status", "createdAt");
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    @GetMapping("/deletion-requests")
    public List<Map<String, Object>> deletions() {
        try {
            return Projection.pickAll(downstream.deletionRequests(), "id", "userRef", "status", "createdAt", "graceEndsAt");
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    @PostMapping("/email-change-requests/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable UUID id, HttpServletRequest request) {
        return run(request, "EMAIL_CHANGE_CONFIRM", id,
                () -> downstream.emailChangeConfirm(id, request.getHeader(HttpHeaders.AUTHORIZATION)));
    }

    @PostMapping("/deletion-requests/{id}/{action}")
    public Map<String, Object> deletionAction(@PathVariable UUID id, @PathVariable String action, HttpServletRequest request) {
        if (!DELETION_ACTIONS.contains(action)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown action");
        }
        return run(request, "DELETION_REQUEST_" + action.toUpperCase(), id,
                () -> downstream.deletionAction(id, action, request.getHeader(HttpHeaders.AUTHORIZATION)));
    }

    private Map<String, Object> run(HttpServletRequest request, String action, UUID target, Runnable call) {
        String actor = Actor.id(request);
        try {
            call.run();
        } catch (ApiException e) {
            audit.record(actor, action, target.toString(), e.status().is4xxClientError() ? "DENIED" : "FAILED", e.code());
            throw e;
        }
        audit.record(actor, action, target.toString(), "SUCCESS", null);
        return Map.of("ok", true);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_UNAVAILABLE", "user-service is not reachable");
    }
}

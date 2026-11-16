package com.fintech.admin_service.api;

import com.fintech.admin_service.service.AuditService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    /** The latest 200 admin actions. Actors are shortened to a reference; targets are ids, never personal data. */
    @GetMapping
    public List<Map<String, Object>> latest() {
        return audit.latest(200);
    }
}

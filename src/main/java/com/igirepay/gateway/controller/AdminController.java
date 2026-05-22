package com.igirepay.gateway.controller;

import com.igirepay.gateway.service.AuditAndExpiryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal admin endpoints (Developer's Choice feature).
 *
 * GET /admin/audit-log   → returns the list of every payment attempt.
 * GET /admin/stats       → returns current store size and log size.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AuditAndExpiryService auditService;

    public AdminController(AuditAndExpiryService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/audit-log")
    public ResponseEntity<List<AuditAndExpiryService.AuditEntry>> getAuditLog() {
        return ResponseEntity.ok(auditService.getAuditLog());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "auditEntries", auditService.getAuditLog().size(),
                "description",  "Use /admin/audit-log for full details"
        ));
    }
}

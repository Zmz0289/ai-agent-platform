package com.aiagent.ecommerce.api;

import com.aiagent.ecommerce.application.EcommerceKnowledgeImportService;
import com.aiagent.infrastructure.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/v1/ecommerce")
@RequiredArgsConstructor
public class EcommerceImportController {

    private final EcommerceKnowledgeImportService importService;
    private final Executor taskExecutor;

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importKnowledge(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filePath cannot be empty"));
        }

        // 异步任务不继承 ThreadLocal，必须在提交前捕获租户并显式传递
        String tenantId = TenantContext.currentTenant();
        log.info("Received import request: {} (tenant={})", filePath, tenantId);

        taskExecutor.execute(TenantContext.wrap(() -> {
            try {
                importService.importFromFile(filePath, tenantId);
            } catch (Exception e) {
                log.error("Import failed: {} (tenant={})", filePath, tenantId, e);
            }
        }));

        return ResponseEntity.ok(Map.of(
                "status", "started",
                "filePath", filePath,
                "tenantId", tenantId,
                "message", "Import task started, check logs for progress"
        ));
    }

    @PostMapping("/import/test")
    public ResponseEntity<Map<String, Object>> testImport(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        int limit = Integer.parseInt(request.getOrDefault("limit", "5"));

        if (filePath == null || filePath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filePath cannot be empty"));
        }

        try {
            var allRecords = importService.parseJsonl(filePath);
            var preview = allRecords.size() > limit
                    ? allRecords.subList(0, limit)
                    : allRecords;

            log.info("Test read: {} records (total {})", preview.size(), allRecords.size());

            return ResponseEntity.ok(Map.of(
                    "totalRecords", allRecords.size(),
                    "previewCount", preview.size(),
                    "preview", preview.stream().map(r -> Map.of(
                            "question", r.getQuestion(),
                            "answer", r.getAnswer(),
                            "qaTextLength", r.getQaText().length()
                    )).toList()
            ));

        } catch (Exception e) {
            log.error("Test read failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}

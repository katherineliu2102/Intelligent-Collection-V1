package com.collection.admin.web;

import com.collection.admin.service.CatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 策略/渠道目录只读 API。
 */
@RestController
@RequestMapping("/catalog")
public class CatalogController {

    @Resource
    private CatalogService catalogService;

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return catalogService.overview();
    }

    /** 单模板详情（含完整文案）。 */
    @GetMapping("/template/{slot}")
    public ResponseEntity<Map<String, Object>> template(@PathVariable String slot) {
        Map<String, Object> detail = catalogService.templateDetail(slot);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    /** Email HTML 预览（示例变量渲染）。 */
    @GetMapping(value = "/template/{slot}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> templatePreview(@PathVariable String slot) throws Exception {
        String html = catalogService.emailPreviewHtml(slot);
        if (html == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Preview not available");
        }
        return ResponseEntity.ok(html);
    }
}

package com.collection.admin.web;

import com.collection.admin.dashboard.DashboardQueryService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 数据分析看板 REST（设计文档 §5.1）。查询在 {@link DashboardQueryService}。 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardQueryService queries;

    public DashboardController(DashboardQueryService queries) {
        this.queries = queries;
    }

    /** 今日执行：五槽 + 分渠道触达 + AI 波次 + 日切断言 + 风险（§5.1.3 / §5.1.7）。 */
    @GetMapping("/today")
    public Map<String, Object> today() {
        return ApiResponse.success(queries.todayExecution());
    }

    @GetMapping("/outreach/realtime")
    public Map<String, Object> outreachRealtime(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(queries.outreachRealtime(days));
    }

    @GetMapping("/portfolio")
    public Map<String, Object> portfolio() {
        return ApiResponse.success(queries.portfolio());
    }

    @GetMapping("/aging")
    public Map<String, Object> aging() {
        return ApiResponse.success(queries.aging());
    }

    @GetMapping("/matrix")
    public Map<String, Object> matrix(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(queries.matrix(days));
    }

    /** 分渠道按日送达（复盘趋势，禁止跨渠道合并）。 */
    @GetMapping("/daily-by-channel")
    public Map<String, Object> dailyByChannel(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(queries.dailyByChannel(days));
    }

    @GetMapping("/aicall/realtime")
    public Map<String, Object> aicallRealtime(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "false") boolean includeSynthetic) {
        return ApiResponse.success(queries.aicallRealtime(days, includeSynthetic));
    }

    @GetMapping("/aicall/detail")
    public Map<String, Object> aicallDetail(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "false") boolean includeSynthetic) {
        return ApiResponse.success(queries.aicallDetail(page, pageSize, days, includeSynthetic));
    }

    @GetMapping("/risk")
    public Map<String, Object> risk() {
        return ApiResponse.success(queries.risk());
    }
}

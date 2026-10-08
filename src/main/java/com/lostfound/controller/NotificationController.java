package com.lostfound.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.common.Result;
import com.lostfound.dto.NotificationView;
import com.lostfound.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站内通知接口。
 *
 * <p>鉴权沿用项目统一的「按 HTTP 方法分」规则：GET 放行、PUT 必须带 token。
 * 但四个接口都**必须知道"你是谁"**才能工作 —— 两个 GET 拿不到 userId 就返回未授权，
 * 两个 PUT 由拦截器保证有 token、再由 Service 按 receiverId 做越权检查。
 * <p>和认领模块一个道理：<b>GET 放行不等于接口是公开的</b>，数据范围由 Service 收口。
 */
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 我的通知（分页；每条内联它包含的匹配明细） */
    @GetMapping("/my")
    public Result<Map<String, Object>> my(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "10") int size,
                                          @RequestParam(defaultValue = "false") boolean unreadOnly,
                                          HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (userId == null) {
            return Result.unauthorized();
        }
        Page<NotificationView> result = notificationService.pageByReceiver(userId, page, size, unreadOnly);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("size", result.getSize());
        return Result.ok(data);
    }

    /** 未读条数 —— 右上角角标用，返回体极小 */
    @GetMapping("/unread-count")
    public Result<Map<String, Object>> unreadCount(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (userId == null) {
            return Result.unauthorized();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", notificationService.unreadCount(userId));
        return Result.ok(data);
    }

    /** 标记单条已读（仅本人；重复调用幂等） */
    @PutMapping("/{id}/read")
    public Result<String> markRead(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        notificationService.markRead(id, userId);
        return Result.ok("已标记为已读", null);
    }

    /** 全部标记已读 */
    @PutMapping("/read-all")
    public Result<Map<String, Object>> markAllRead(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updated", notificationService.markAllRead(userId));
        return Result.ok("已全部标记为已读", data);
    }
}

package com.lostfound.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.common.BusinessException;
import com.lostfound.common.Result;
import com.lostfound.common.ResultCode;
import com.lostfound.dto.ItemCreateRequest;
import com.lostfound.dto.ItemDetailView;
import com.lostfound.dto.ItemPageQuery;
import com.lostfound.entity.Item;
import com.lostfound.service.AutoMatchService;
import com.lostfound.service.ClaimService;
import com.lostfound.service.FileService;
import com.lostfound.service.ItemService;
import com.lostfound.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/item")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;
    private final UserService userService;
    private final FileService fileService;
    private final ClaimService claimService;
    /** 发布成功后触发后台自动匹配（异步） */
    private final AutoMatchService autoMatchService;

    /**
     * 发布物品（需登录，支持图片上传）。
     * 使用 multipart/form-data 格式：
     * - data: JSON 字符串（title, type, category 等）
     * - image: 图片文件（可选）
     */
    @PostMapping
    public Result<Item> publish(@Valid @RequestPart("data") ItemCreateRequest request,
                                @RequestPart(value = "image", required = false) MultipartFile image,
                                HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        // Controller 负责处理文件上传，Service 只收 URL 字符串
        String imageUrl = fileService.upload(image);
        Item item = itemService.publish(userId, request, imageUrl);

        // 发布成功后再触发后台自动匹配（异步，立刻返回，不阻塞这个请求）。
        //
        // 为什么触发点放在 Controller 而不是 ItemServiceImpl.publish 里：
        // AutoMatchService → AiService → CandidateService → ItemService，
        // 而 ItemServiceImpl 依赖 AutoMatchService 就构成了**构造器注入解决不了的循环依赖**
        // （这个项目用的是 @RequiredArgsConstructor，不是字段注入）。
        // 放在这里既避开了环，又天然是"insert 已提交之后"——publish 没有事务，insert 返回即落库。
        //
        // ⚠️ 如果将来给 publish 加上 @Transactional，这个位置就不再是"提交后"了，
        //    必须改成 afterCommit 回调，否则异步任务可能查不到刚发布的物品。
        try {
            autoMatchService.triggerAfterPublish(item);
        } catch (Exception e) {
            // 兜底：自动匹配的任何问题都不能影响"发布成功"这个结果
            log.warn("触发自动匹配失败（不影响发布）: itemId={}", item.getId(), e);
        }
        return Result.ok("发布成功", item);
    }

    /** 物品列表（分页 + 筛选） */
    @GetMapping
    public Result<Map<String, Object>> list(ItemPageQuery query) {
        Page<Item> page = itemService.page(query);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        result.put("page", page.getCurrent());
        result.put("size", page.getSize());
        return Result.ok(result);
    }

    /**
     * 物品详情（不需要登录也能看，但联系方式按身份收敛）。
     *
     * <p>联系方式只给两种人：<b>发布者本人</b>和<b>已通过审核的申请人</b>。
     * 这不是"少给点信息"，而是认领流程成立的前提 —— 如果谁都能直接看到电话，
     * 失主根本不会去走申请流程，整套机制就形同虚设。
     * <p>判断逻辑在 Service，这里只负责把结论拼进返回体。
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        Item item = itemService.getById(id);
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "物品不存在");
        }
        Long userId = (Long) httpRequest.getAttribute("userId");
        String nickname = userService.getNicknameById(item.getUserId());

        boolean isOwner = item.getUserId().equals(userId);
        boolean contactVisible = isOwner || claimService.canSeeContact(id, userId);
        // 发布者自己不需要看"我的申请状态"
        String myClaimStatus = isOwner ? null : claimService.activeClaimStatus(id, userId);

        // ⚠️ 返回的是复制出来的视图，不是缓存里那个 item 对象 —— 详见 ItemDetailView 的类注释
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("item", ItemDetailView.from(item, contactVisible, myClaimStatus));
        result.put("publisherName", nickname);
        return Result.ok(result);
    }

    /** 我的发布列表（需登录） */
    @GetMapping("/my")
    public Result<Map<String, Object>> myItems(ItemPageQuery query,
                                               HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (userId == null) {
            return Result.unauthorized();
        }
        Page<Item> page = itemService.pageByUserId(userId, query);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        result.put("page", page.getCurrent());
        result.put("size", page.getSize());
        return Result.ok(result);
    }

    /** 修改物品状态（需登录，仅发布者本人） */
    @PutMapping("/{id}/status")
    public Result<String> updateStatus(@PathVariable Long id,
                                     @RequestParam String status,
                                     HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        itemService.updateStatus(id, userId, status);
        // 显式传两个参数，避免走 ok(T data) 重载把"更新成功"误当成 data
        return Result.ok("更新成功", null);
    }
}

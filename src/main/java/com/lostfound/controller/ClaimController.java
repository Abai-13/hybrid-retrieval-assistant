package com.lostfound.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.common.Result;
import com.lostfound.dto.ClaimApplyRequest;
import com.lostfound.dto.ClaimCompleteResult;
import com.lostfound.dto.ClaimDetailView;
import com.lostfound.dto.ClaimPageQuery;
import com.lostfound.dto.ClaimRejectRequest;
import com.lostfound.entity.ClaimApplication;
import com.lostfound.service.ClaimService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认领申请接口。
 *
 * <p><b>鉴权</b>沿用项目统一的「按 HTTP 方法分」规则：POST / PUT / DELETE 必须带有效 token，
 * GET 放行。但这里的三个接口都<b>必须知道"你是谁"</b>才能工作：
 * <ul>
 *   <li>提交申请是 POST —— 拦截器已保证有 token；</li>
 *   <li>两个查询是 GET —— 拦截器放行，但拿不到 userId 就没法限定范围，
 *       所以返回 {@code Result.unauthorized()}，而不是把别人的数据当成公开列表返回。</li>
 * </ul>
 * 换句话说：<b>GET 放行不等于这个接口是公开的</b>，数据范围由 Service 层按 userId 强制收口。
 *
 * <p>⚠️ 尤其注意 {@code /received}：它返回的是<b>别人提交的特征证明</b>。
 * 那是验真凭据，泄露给第三方等于公开答案，人人都能照抄去冒领 ——
 * 所以它必须锁死在「我发布的物品」这个范围内。
 */
@RestController
@RequestMapping("/api/claim")
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService claimService;

    /** 提交认领申请（需登录） */
    @PostMapping
    public Result<ClaimApplication> apply(@Valid @RequestBody ClaimApplyRequest request,
                                          HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        ClaimApplication claim = claimService.apply(userId, request);
        return Result.ok("申请已提交，等待发布者审核", claim);
    }

    /** 我发起的认领申请（需登录，只能看到自己的） */
    @GetMapping("/my")
    public Result<Map<String, Object>> myClaims(ClaimPageQuery query,
                                                HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (userId == null) {
            return Result.unauthorized();
        }
        return Result.ok(toPageMap(claimService.pageByApplicant(userId, query)));
    }

    /** 我发布的物品收到的认领申请（需登录，只含自己发布的物品） */
    @GetMapping("/received")
    public Result<Map<String, Object>> receivedClaims(ClaimPageQuery query,
                                                      HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (userId == null) {
            return Result.unauthorized();
        }
        return Result.ok(toPageMap(claimService.pageByOwner(userId, query)));
    }

    /**
     * 审核通过（需登录，且必须是该物品的发布者）。
     * <p>通过之后物品状态<b>不变</b>，仍是「认领中」—— 归还由申请人确认，见 CLAUDE.md 的认领约定。
     */
    @PostMapping("/{id}/approve")
    public Result<Map<String, Object>> approve(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        claimService.approve(id, userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claimId", id);
        data.put("status", ClaimApplication.STATUS_APPROVED);
        return Result.ok("已通过该认领申请", data);
    }

    /** 驳回（需登录，且必须是该物品的发布者）；驳回理由必填 */
    @PostMapping("/{id}/reject")
    public Result<Map<String, Object>> reject(@PathVariable Long id,
                                              @Valid @RequestBody ClaimRejectRequest request,
                                              HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        claimService.reject(id, userId, request.getReason());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claimId", id);
        data.put("status", ClaimApplication.STATUS_REJECTED);
        return Result.ok("已驳回该认领申请", data);
    }

    /**
     * 确认收到物品（需登录，且必须是申请人本人）。
     * <p>这是唯一的「物品变成已认领」入口：先把申请推到 COMPLETED，物品才跟着转 CLAIMED，
     * 同一事务里还会把该物品下其他还挂着的申请自动关闭（条数在 {@code closedOthers} 里返回）。
     * <p>幂等：重复调用不会重复执行，直接返回成功。
     */
    @PostMapping("/{id}/complete")
    public Result<ClaimCompleteResult> complete(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        ClaimCompleteResult result = claimService.complete(id, userId);
        String message = result.getClosedOthers() > 0
                ? "已确认收到，另有 " + result.getClosedOthers() + " 条申请已自动关闭"
                : "已确认收到，物品标记为已认领";
        return Result.ok(message, result);
    }

    /**
     * 申请详情，含完整流转时间线（需登录，仅申请人与物品发布者可见）。
     * <p>注意这个映射是 {@code /{id}}，不会抢走 {@code /my} 和 {@code /received} ——
     * Spring 在路径匹配上让字面量优先于路径变量。
     */
    @GetMapping("/{id}")
    public Result<ClaimDetailView> detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.ok(claimService.getDetail(id, userId));
    }

    /** 撤回尚未审核的申请（需登录，仅申请人本人；仅 PENDING 可撤） */
    @PostMapping("/{id}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        claimService.cancel(id, userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claimId", id);
        data.put("status", ClaimApplication.STATUS_CANCELLED);
        return Result.ok("已撤回该认领申请", data);
    }

    /** 与 ItemController 保持一致的分页返回结构，前端不用为两个模块写两套解析 */
    private Map<String, Object> toPageMap(Page<?> page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        result.put("page", page.getCurrent());
        result.put("size", page.getSize());
        return result;
    }
}

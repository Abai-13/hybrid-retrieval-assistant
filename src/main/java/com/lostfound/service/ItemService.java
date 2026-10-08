package com.lostfound.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.dto.ItemCreateRequest;
import com.lostfound.dto.ItemPageQuery;
import com.lostfound.entity.Item;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface ItemService {

    /** 发布物品；imageUrl 由 Controller 层调用 FileService 获得 */
    Item publish(Long userId, ItemCreateRequest request, String imageUrl);

    /** 分页查询物品列表 */
    Page<Item> page(ItemPageQuery query);

    /** 获取物品详情 */
    Item getById(Long id);

    /**
     * 按 id 批量取物品，返回 id → 物品 的映射（认领列表拼装展示信息用）。
     *
     * <p>为什么不直接循环调 {@link #getById}：那是 N 次查询，列表一页 10 条就是 10 次往返。
     * <p>为什么返回 Map 而不是 List：MyBatis-Plus 的 {@code selectBatchIds} <b>不保证返回顺序</b>，
     * 返回 List 就得让每个调用方自己记着"要按原 id 顺序还原"；返回 Map 直接从根上绕开这个坑。
     */
    Map<Long, Item> mapByIds(Collection<Long> ids);

    /**
     * 发布者手动改物品状态 —— <b>只允许「撤销」这一个方向</b>：{@code CLAIMED → UNCLAIMED}。
     *
     * <p>正向的状态推进（待认领 → 认领中 → 已认领）全部由认领流程负责，见
     * {@link #changeStatusByClaim} 与 {@link #revertToUnclaimedIfClaiming}。
     * 保留撤销是为了纠错：物归原主之后发现认错了人，得能改回来。
     * 但绝不能靠这个接口把物品直接标成「已认领」—— 那样既没有申请人也没有流转日志。
     */
    void updateStatus(Long itemId, Long userId, String status);

    /**
     * 由认领流程驱动的物品状态变更 —— 认领申请提交/流转时由 ClaimService 调用。
     *
     * <p>和 {@link #updateStatus} 的区别：那个是<b>发布者主动</b>改状态，要校验身份；
     * 这个是<b>流程副作用</b>，权限由认领流程自己校验，物品侧只负责按规则搬运状态。
     *
     * <p>⚠️ 必须以对外 public 方法的形式存在，不能退化成一个私有方法：
     * 上面的 {@code @CacheEvict} 基于 Spring 代理生效，同类内部自调用会<b>静默失效</b>，
     * 结果就是「状态变了但缓存没清」——表现出来是详情页还显示旧状态、AI 仍按旧状态召回。
     *
     * @param expectedStatus 期望的当前状态（CAS 语义）。与库里实际状态不符时抛 409，
     *                       而不是硬改 —— 这是防并发覆盖的关键，不能省。
     * @param targetStatus   目标状态
     */
    void changeStatusByClaim(Long itemId, String expectedStatus, String targetStatus);

    /**
     * 把「认领中」的物品退回「待认领」—— 当一条物品上再也<b>没有任何活跃申请</b>时调用。
     *
     * <p>为什么单独一个方法而不是复用 {@link #changeStatusByClaim}：
     * 那条路的语义是「必须从期望状态变到目标状态」，状态不对就抛 409。
     * 但这里的情况恰恰相反 —— 物品可能已经变成 {@code CLAIMED}（别的申请人已经完成了归还），
     * 此时驳回一条历史遗留的待审申请是<b>完全正常</b>的操作，不该因为它而被拦下。
     * 所以这里的契约是<b>尽力而为、幂等</b>：物品不是 CLAIMING 就什么都不做，也不报错。
     */
    void revertToUnclaimedIfClaiming(Long itemId);

    /** 分页查询当前用户的物品列表（个人中心用） */
    Page<Item> pageByUserId(Long userId, ItemPageQuery query);

    /** 查询所有未被认领的物品信息 */
    List<Item> selectList(ItemPageQuery query);

    /**
     * 查询 AI 召回用的候选全集 —— 全部「还没被认领走」的招领物品。
     * <p>
     * 含 {@code UNCLAIMED} 和 {@code CLAIMING} 两种状态，<b>只排除 {@code CLAIMED}</b>。
     * 理由是「有人提交了申请」不等于「物品已经归还」——申请可能被驳回，
     * 第二个人仍然应该能搜到它并排队申请。若把 CLAIMING 排除掉，
     * 等于第一个提交申请的人把物品从 AI 视野里藏了起来。
     * <p>
     * 和 {@link #page} 的区别：那个是给页面分页用的（限制 50 条、按时间倒序），
     * 这个方法要的是完整集合，用来在应用层做相关性打分。
     * 结果带缓存，靠发布/改状态时的 CacheEvict 保证新鲜度。
     */
    List<Item> listUnclaimedFound();

    /**
     * 查询还在找的失物全集 —— 给「发布招领后反向匹配失物」用。
     * <p>
     * 和 {@link #listUnclaimedFound} 是镜像：那个给"失物描述 → 找招领"，
     * 这个给"招领描述 → 找失物"。同样带缓存（{@code lostPool}），
     * 同样必须在所有会改物品状态的写路径上清掉。
     */
    List<Item> listUnclaimedLost();
}

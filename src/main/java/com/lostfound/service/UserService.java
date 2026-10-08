package com.lostfound.service;

import com.lostfound.dto.LoginResponse;
import com.lostfound.dto.RegisterRequest;
import com.lostfound.entity.User;

import java.util.Collection;
import java.util.Map;

public interface UserService {

    /** 注册，返回新用户 ID */
    Long register(RegisterRequest request);

    /** 登录，返回 token 等信息 */
    LoginResponse login(String username, String password);

    /** 根据 ID 查询用户名等信息（用于展示发帖人） */
    String getNicknameById(Long userId);

    /**
     * 按 id 批量取昵称，返回 id → 昵称 的映射（认领审核台展示申请人用）。
     * <p>存在的理由和 {@code ItemService#mapByIds} 一样：避免列表页 N+1。
     */
    Map<Long, String> mapNicknamesByIds(Collection<Long> ids);

    /** 根据 ID 查询用户完整信息（不含密码） */
    User getUserById(Long userId);
}

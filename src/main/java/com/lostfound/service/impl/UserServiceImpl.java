package com.lostfound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lostfound.common.BusinessException;
import com.lostfound.common.ResultCode;
import com.lostfound.config.JwtUtil;
import com.lostfound.dto.LoginResponse;
import com.lostfound.dto.RegisterRequest;
import com.lostfound.entity.User;
import com.lostfound.mapper.UserMapper;
import com.lostfound.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public Long register(RegisterRequest request) {
        // 1. 检查用户名是否已存在
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, request.getUsername()));
        if (count > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名已被注册");
        }

        // 2. 构建用户并保存
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        user.setPhone(request.getPhone());
        user.setRole("USER");

        userMapper.insert(user);
        log.info("新用户注册: id={}, username={}", user.getId(), user.getUsername());
        return user.getId();
    }

    @Override
    public LoginResponse login(String username, String rawPassword) {
        // 1. 查用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username));
        if (user == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名或密码错误");
        }

        // 2. 验密码
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名或密码错误");
        }

        // 3. 生成 token
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", user.getUsername());
        claims.put("role", user.getRole());
        String token = jwtUtil.createToken(user.getId(), claims);

        log.info("用户登录: id={}, username={}", user.getId(), user.getUsername());
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getNickname());
    }

    @Override
    public String getNicknameById(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null ? user.getNickname() : "未知用户";
    }

    @Override
    public Map<Long, String> mapNicknamesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        // 用循环而不是 Collectors.toMap：toMap 遇到 null 值会抛 NPE，
        // 而 nickname 是允许为空的字段（注册时虽然会兜底成 username，但老数据不保证）
        for (User user : userMapper.selectBatchIds(ids)) {
            String nickname = user.getNickname();
            if (nickname == null) {
                nickname = user.getUsername() != null ? user.getUsername() : "未知用户";
            }
            result.put(user.getId(), nickname);
        }
        return result;
    }

    @Override
    public User getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }
}

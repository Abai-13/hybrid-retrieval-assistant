package com.lostfound.service.impl;

import com.lostfound.common.BusinessException;
import com.lostfound.common.ResultCode;
import com.lostfound.config.JwtUtil;
import com.lostfound.dto.LoginResponse;
import com.lostfound.dto.RegisterRequest;
import com.lostfound.entity.User;
import com.lostfound.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl 单元测试（纯 Mockito，不起 Spring 上下文，不依赖 MySQL/Redis）。
 * 覆盖：注册（重名校验 + BCrypt 加密）、登录（密码校验 + token 生成）。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private UserServiceImpl userService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private RegisterRequest buildRequest(String username, String password) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setPassword(password);
        req.setNickname("测试昵称");
        req.setPhone("13800000000");
        return req;
    }

    @Test
    @DisplayName("注册：用户名已存在 → 抛业务异常 400，且不落库")
    void register_duplicateUsername_shouldThrow() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register(buildRequest("admin", "123456")));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertEquals("用户名已被注册", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("注册：密码 BCrypt 加密存储，不存明文")
    void register_passwordShouldBeEncrypted() {
        when(userMapper.selectCount(any())).thenReturn(0L);

        userService.register(buildRequest("admin", "123456"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();

        assertNotEquals("123456", saved.getPassword(), "密码不能存明文");
        assertTrue(encoder.matches("123456", saved.getPassword()), "应能用 BCrypt 校验");
        assertEquals("USER", saved.getRole(), "默认角色应为 USER");
    }

    @Test
    @DisplayName("登录：密码错误 → 抛业务异常 400")
    void login_wrongPassword_shouldThrow() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword(encoder.encode("right-password"));
        when(userMapper.selectOne(any())).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login("admin", "wrong-password"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertEquals("用户名或密码错误", ex.getMessage());
    }

    @Test
    @DisplayName("登录：成功 → 返回 token")
    void login_success_shouldReturnToken() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword(encoder.encode("123456"));
        user.setNickname("管理员");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(jwtUtil.createToken(eq(1L), any())).thenReturn("fake-token");

        LoginResponse resp = userService.login("admin", "123456");

        assertNotNull(resp);
        assertEquals("fake-token", resp.getToken());
        assertEquals("admin", resp.getUsername());
    }
}

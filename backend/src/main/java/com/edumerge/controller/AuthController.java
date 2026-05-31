package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.common.result.ResultCode;
import com.edumerge.dto.CreateUserRequest;
import com.edumerge.dto.LoginRequest;
import com.edumerge.dto.LoginResponse;
import com.edumerge.dto.UserResponse;
import com.edumerge.entity.User;
import com.edumerge.security.JwtUtils;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final JwtUtils jwtUtils;

    public AuthController(UserService userService, JwtUtils jwtUtils) {
        this.userService = userService;
        this.jwtUtils = jwtUtils;
    }

    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody CreateUserRequest req) {
        if (userService.existsByUsername(req.getUsername())) {
            return Result.fail(ResultCode.USER_ALREADY_EXISTS.getCode(), "用户名已被注册");
        }
        if (userService.existsByEmail(req.getEmail())) {
            return Result.fail(ResultCode.USER_ALREADY_EXISTS.getCode(), "邮箱已被注册");
        }

        User user = userService.register(req.getUsername(), req.getEmail(),
                req.getPassword(), req.getDisplayName());
        String token = jwtUtils.generateToken(user.getId(), user.getUsername());

        LoginResponse resp = LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(86400000L)
                .user(toResponse(user))
                .build();
        return Result.success("注册成功", resp);
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        User user = userService.login(req.getUsername(), req.getPassword());
        if (user == null) {
            return Result.fail(ResultCode.INVALID_CREDENTIALS.getCode(), "用户名或密码错误");
        }

        String token = jwtUtils.generateToken(user.getId(), user.getUsername());
        LoginResponse resp = LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(86400000L)
                .user(toResponse(user))
                .build();
        log.info("用户登录成功: id={}, username={}", user.getId(), user.getUsername());
        return Result.success("登录成功", resp);
    }

    @GetMapping("/profile")
    public Result<UserResponse> profile() {
        Long userId = SecurityUtils.getCurrentUserId();
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail(ResultCode.USER_NOT_EXIST.getCode(), "用户不存在");
        }
        return Result.success(toResponse(user));
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}

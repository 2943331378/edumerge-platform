package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.User;
import com.edumerge.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(String username, String email, String password, String displayName) {
        User user = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(password))
                .displayName(displayName)
                .status(1)
                .build();
        userMapper.insert(user);
        log.info("用户注册成功: id={}, username={}", user.getId(), username);
        return user;
    }

    @Transactional(readOnly = true)
    public User login(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            return null;
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return null;
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return null;
        }
        return user;
    }

    @Transactional(readOnly = true)
    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0;
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)) > 0;
    }
}

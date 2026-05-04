package com.edumerge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体类（示例）
 * 使用 MyBatis-Plus 注解
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("users")
public class User {

    /**
     * 用户ID（主键，自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户名（唯一）
     */
    private String username;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 密码（加密后）
     */
    private String password;

    /**
     * 显示名称
     */
    private String displayName;

    /**
     * 用户状态：0=禁用，1=启用
     */
    private Integer status;

    /**
     * 逻辑删除标记（0=未删除，1=已删除）
     */
    @TableLogic
    private Integer deleted;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}

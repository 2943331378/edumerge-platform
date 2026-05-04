package com.edumerge.utils;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分页请求对象
 * 前端请求列表数据时使用
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PageRequest {

    /**
     * 当前页码（从 1 开始）
     */
    @Builder.Default
    private Integer pageNum = 1;

    /**
     * 每页条数
     */
    @Builder.Default
    private Integer pageSize = 10;

    /**
     * 排序字段
     */
    private String sortBy;

    /**
     * 排序方向: asc 或 desc
     */
    @Builder.Default
    private String sortOrder = "desc";

    public Integer getPageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    public Integer getPageSize() {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        // 最大不超过 100 条
        return Math.min(pageSize, 100);
    }
}

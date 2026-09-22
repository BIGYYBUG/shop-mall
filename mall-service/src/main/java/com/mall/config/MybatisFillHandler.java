package com.mall.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 字段自动填充。
 *
 * <p>BaseEntity 上的 {@code @TableField(fill = ...)} 只是"标记"，
 * 真正把时间写进去的活儿由本处理器完成 —— 缺了它，createTime / updateTime 会是 null。</p>
 */
@Component
public class MybatisFillHandler implements MetaObjectHandler {

    /** 插入时：同时填创建时间和更新时间 */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    /** 更新时：只刷新更新时间 */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}

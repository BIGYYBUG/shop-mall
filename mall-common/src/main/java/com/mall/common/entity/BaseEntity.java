package com.mall.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 基础实体：id / 创建时间 / 更新时间 / 逻辑删除标记。
 *
 * <p><b>继承约定</b>：数据库里同时带 {@code create_time}、{@code update_time}、
 * {@code deleted} 三列的表，实体才继承本类。</p>
 *
 * <p><b>反例</b>：{@code mall_user_role}、{@code mall_role_permission} 这类纯关联表
 * 只有 id 和 create_time，<b>绝不能继承本类</b>。否则 MyBatis-Plus 会向并不存在的
 * {@code update_time} / {@code deleted} 列写数据，直接报 Unknown column。</p>
 */
@Data
public class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 创建时间，插入时自动填充（由 MybatisFillHandler 完成） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，插入与更新时自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记：<b>0 未删除；已删除时写入本行自己的 id</b>。
     *
     * <p>标注 {@code @TableLogic} 之后，MyBatis-Plus 会自动改造 SQL：</p>
     * <ul>
     *   <li>查询：追加 {@code WHERE deleted = 0}</li>
     *   <li>{@code deleteById()}：实际执行 {@code UPDATE ... SET deleted = id}</li>
     * </ul>
     *
     * <h3>为什么删除值是 id 而不是 1</h3>
     *
     * <p>业务表的唯一键都带上了 {@code deleted}，例如
     * {@code uk_username (username, deleted)}。这类索引的目的是
     * 「允许注销后重新注册同名账号」，但如果删除值恒为 1，
     * 同一个用户名在索引里只能放两行：{@code (abc, 0)} 和 {@code (abc, 1)}。
     * 于是「注销 → 重注 → 再注销」时，第二次注销要把新行也写成
     * {@code (abc, 1)} —— 与第一条已删除的行完全撞车，直接抛
     * {@code Duplicate entry 'abc-1' for key 'uk_username'}。</p>
     *
     * <p>把删除值改成主键 id 之后，每一行被删时写入的都是自己的 id，
     * 而 id 由自增保证互不相同，于是唯一索引里变成
     * {@code (abc, 5)}、{@code (abc, 9)} …… 天然不重复，注销回数不受限制。</p>
     *
     * <h3>三条不能违反的约束</h3>
     * <ol>
     *   <li><b>数据库列类型必须是 BIGINT</b>。TINYINT 上限 127，
     *       而 {@code mall_user.id} 早已超过这个值，写进去会 Out of range。</li>
     *   <li><b>绝不能用 NULL 表示未删除</b>。MySQL 唯一索引不约束 NULL，
     *       允许多行 {@code (abc, NULL)} 并存，唯一约束会当场失效 ——
     *       比原来的坑更严重。未删除一律是 0。</li>
     *   <li><b>新增带唯一键的业务表要照抄这个模式</b>：唯一键写成
     *       {@code (业务列, deleted)}，且 deleted 用 BIGINT。</li>
     * </ol>
     *
     * <p>{@code select = false} 让该列不出现在查询结果中 —— 业务代码和前端
     * 都不需要感知它，属于纯粹的存储层细节。</p>
     */
    @TableLogic(value = "0", delval = "id")
    @TableField(select = false)
    private Long deleted;
}

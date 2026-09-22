package com.mall.convert;

import java.util.Collection;
import java.util.List;

/**
 * VO 装配工厂的统一契约。
 *
 * <p><b>要解决的问题</b>：{@code new UserVO(); vo.setId(...); vo.setUsername(...); ...}
 * 这种代码一旦散落在 Controller、Service、定时任务里，就会出现同一个 VO
 * 有好几份装配实现。本项目就真实出现过：{@code UserServiceImpl} 和
 * {@code SocialLoginServiceImpl} 各写了一份 {@code toVO(UserEntity, List<String>)}，
 * 内容一字不差 —— 只要有人给 {@code UserVO} 加了字段，就必然漏改其中一处，
 * 表现为"登录接口返回的头像有值、用户列表接口返回的头像为空"这类幽灵问题。</p>
 *
 * <p><b>这一层带来什么</b>：</p>
 * <ol>
 *   <li><b>单一入口</b> —— 一个 VO 只有一处装配代码，加字段只需改一个类；</li>
 *   <li><b>Controller 变薄</b> —— 它只负责 HTTP 编排（取参、调服务、包 Result），
 *       不承担数据搬运；</li>
 *   <li><b>可独立测试</b> —— 装配规则（null 怎么处理、字段怎么算）能用纯单元测试覆盖，
 *       不必启动 Web 容器；</li>
 *   <li><b>依赖可注入</b> —— 比如 {@code ProductVoFactory} 需要 {@code FileStorageService}
 *       把 objectKey 拼成 URL，做成 Bean 后直接注入，不需要每个调用方都塞一遍。</li>
 * </ol>
 *
 * <p><b>为什么不放在 VO 里做静态方法</b>：{@code ChatVO} 在 {@code mall-api}、
 * {@code LlmResponse} 在 {@code mall-service}，而 Maven 依赖方向是
 * {@code mall-web → mall-service → mall-api}。让 api 层的 VO 去引用 service 层的类型
 * 会造成循环依赖 —— 同样的原因，见 {@code PermissionChecker} 的 SPI 设计。</p>
 *
 * @param <S> 来源类型（通常是 Entity，也可以是外部接口的响应对象）
 * @param <T> 目标 VO 类型
 */
public interface VoFactory<S, T> {

    /**
     * 把来源对象装配成 VO。
     *
     * @param source 来源对象，实现方需自行约定是否为 null
     * @return VO 实例
     */
    T create(S source);

    /**
     * 批量装配。
     *
     * <p>默认实现是逐个调 {@code create}。子类若需要批量取关联数据
     * （例如一次性把本页所有用户的角色捞回来），可覆盖本方法以避免 N+1 查询。</p>
     */
    default List<T> createList(Collection<S> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream().map(this::create).toList();
    }
}

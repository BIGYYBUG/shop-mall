package com.mall.storage.memory;

import com.mall.service.ai.ChatMessage;
import com.mall.storage.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版对话记忆存储：仅用于本地开发与单元测试，<b>进程重启即全部丢失</b>。
 *
 * <p>与 {@code local/LocalFileStorageService}、{@code oss/AliyunOssFileStorageService}
 * 一样按渠道分子包 —— 后面加 {@code redis/}、{@code db/} 实现时结构就齐了。</p>
 *
 * <p>它的价值是验证 {@link ChatMemoryStore} 的契约好不好实现 ——
 * 如果连内存版都写不顺，说明接口设计有问题。</p>
 *
 * <h2>核心陷阱：容器线程安全不等于容器里的东西线程安全</h2>
 * {@link ConcurrentHashMap} 只保证「键到值的映射关系」是并发安全的
 * （{@code put}/{@code get}/{@code computeIfAbsent} 各自原子），
 * <b>但它完全不保护装着的那个 {@code ArrayList}</b>。
 * 两个线程经 {@code computeIfAbsent} 拿到的是<b>同一个 list 实例</b>，
 * 之后的 {@code addAll} 就是在并发修改同一个 {@code ArrayList}：
 * <ul>
 *   <li>{@code ArrayList.add} 的实现是 {@code elementData[size++] = e}，
 *       两句之间没有 happens-before 保证 —— 另一个线程可能先看到 size 已 +1
 *       而槽位还是 null，留下 <b>null 空洞</b>，之后 {@code List.copyOf} 撞上它直接 NPE；</li>
 *   <li>两个线程读到同一个 size、先后写同一槽位 → <b>丢消息</b>；</li>
 *   <li>扩容竞争下还可能抛 {@code ArrayIndexOutOfBoundsException}。</li>
 * </ul>
 *
 * <p>所以下面两个方法都对 list 本身加锁。<b>锁对象就是 list 自己</b>，
 * 于是锁的粒度是「每个会话一把锁」—— 会话 A 的写入不会阻塞会话 B。</p>
 *
 * <p><b>为什么不用 {@code CopyOnWriteArrayList}</b>：它每次 {@code addAll} 都复制整个
 * 底层数组，写满 n 条消息的累计代价是 <b>O(n²)</b>，还会给 GC 制造大量垃圾。
 * 它的设计场景是「读极多、写极少、列表很小」（如监听器列表），对话历史恰好相反。</p>
 */
@Component
// 第 2 课要改这里：新增 Redis / DB 实现后，同一类型会出现多个候选 Bean，
// Spring 注入时无法抉择会直接启动失败。届时改为：
// @ConditionalOnProperty(name = "mall.storage.chat.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final Map<String, List<ChatMessage>> histories = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getHistory(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId 不能为 null");

        List<ChatMessage> list = histories.get(conversationId);
        if (list == null) {
            // 契约要求：会话不存在时返回空列表而非 null，
            // 让调用方不必写 null 判断，避免 NPE 顺着调用链往上冒。
            return List.of();
        }
        // 必须持锁复制：List.copyOf 要遍历 list，
        // 若此刻另一线程正在 addAll，无锁遍历会抛 ConcurrentModificationException。
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    @Override
    public void append(String conversationId, List<ChatMessage> messages) {
        Objects.requireNonNull(conversationId, "conversationId 不能为 null");
        if (messages == null || messages.isEmpty()) {
            // 契约要求：空集合忽略而非报错。幂等永远比抛异常好。
            return;
        }

        List<ChatMessage> list = histories.computeIfAbsent(conversationId, key -> new ArrayList<>());

        // 关键：computeIfAbsent 只保证「拿到 list」这一步原子，
        // 拿到之后的 addAll 不受任何保护，必须自己圈临界区。
        synchronized (list) {
            list.addAll(messages);
        }
    }

    @Override
    public void clear(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId 不能为 null");

        // 内存版没有「会话实体」，所以「清空消息」与「移除键」等价。
        // 第 3 课有了 mall_chat_conversation 后，clear 仍然只删消息、不删会话行。
        // remove 本身幂等：会话不存在时静默返回，不抛异常。
        histories.remove(conversationId);
    }
}

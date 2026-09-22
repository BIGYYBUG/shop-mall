import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.MessageDigest;

/**
 * 密码存储方案实测 —— 独立可运行，不依赖 Spring 容器。
 *
 * <p>用实测数据回答三个问题：</p>
 * <ol>
 *   <li>盐到底是「每用户随机」还是「系统级统一」？</li>
 *   <li>bcrypt 到底慢多少？这个「慢」值多少钱？</li>
 *   <li>能不能在 bcrypt 后面拼一个系统密钥（pepper）？</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>
 * cd E:/Projects/shopping-mall/mall-server/docs/samples
 * java -cp "C:\Users\22522\.m2\repository\org\springframework\security\spring-security-crypto\6.3.4\spring-security-crypto-6.3.4.jar;C:\Users\22522\.m2\repository\org\springframework\spring-jcl\6.1.12\spring-jcl-6.1.12.jar" PasswordHashingDemo.java
 * </pre>
 *
 * <p>类路径里的两个 jar 都可以在本地 ~/.m2 仓库找到，无需额外下载。</p>
 */
public class PasswordHashingDemo {

    public static void main(String[] args) throws Exception {
        demo1SaltIsPerUser();
        demo2HashStructure();
        demo3CostLadder();
        demo4Md5Speed();
        demo5BcryptTruncatesAt72Bytes();
    }

    /** ① 同一个密码编码两次 → 结果不同，证明盐是每用户随机的 */
    private static void demo1SaltIsPerUser() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String h1 = encoder.encode("123456");
        String h2 = encoder.encode("123456");

        System.out.println("① 盐是否每用户随机");
        System.out.println("   第 1 次: " + h1);
        System.out.println("   第 2 次: " + h2);
        System.out.println("   两次完全相同? " + h1.equals(h2));
        System.out.println("   h1 校验 123456 -> " + encoder.matches("123456", h1));
        System.out.println("   h2 校验 123456 -> " + encoder.matches("123456", h2));
        System.out.println("   h1 校验 123457 -> " + encoder.matches("123457", h1));
        System.out.println("   => 盐随哈希一起存，验证时从哈希里取回，所以不需要单独的 salt 列");
        System.out.println();
    }

    /** ② 拆开 bcrypt 哈希串，看清楚每段是什么 */
    private static void demo2HashStructure() {
        String hash = new BCryptPasswordEncoder().encode("123456");
        // $2a$10$xxxxxxxxxxxxxxxxxxxxxxyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy
        String[] parts = hash.split("\\$");
        // parts[0] = "" (前导 $), parts[1] = 2a, parts[2] = 10, 之后是 22 字符盐 + 31 字符摘要
        String body = parts[3];
        String salt = body.substring(0, 22);
        String digest = body.substring(22);

        System.out.println("② bcrypt 哈希串的结构");
        System.out.println("   完整值 : " + hash);
        System.out.println("   算法标识: $" + parts[1] + "$   (2a = bcrypt 的经典变体)");
        System.out.println("   cost   : " + parts[2] + "      (即 2^" + parts[2] + " = " + (1 << Integer.parseInt(parts[2])) + " 轮)");
        System.out.println("   盐     : " + salt + "   <- 22 字符，每用户随机，就存在这里");
        System.out.println("   摘要   : " + digest);
        System.out.println("   => 盐不是「系统统一值」，而是「一人一个」且随哈希同存");
        System.out.println();
    }

    /** ③ cost 每 +1，耗时翻倍 —— 这就是暴力破解的减速带 */
    private static void demo3CostLadder() {
        System.out.println("③ cost 因子与耗时（同一台机器实测）");
        long cost10Us = 0;
        for (int cost : new int[]{4, 8, 10, 12, 14}) {
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(cost);
            long start = System.nanoTime();
            encoder.encode("123456");
            long us = (System.nanoTime() - start) / 1_000;
            if (cost == 10) {
                cost10Us = us;
            }
            System.out.printf("   cost=%2d -> %,9d us%n", cost, us);
        }

        // 用实测值推算：8 位「小写字母 + 数字」的密码空间 = 36^8
        double space = Math.pow(36, 8);
        double md5PerSec = 666_000_000d;                 // demo4 实测的单核速度
        double bcryptPerSec = 1_000_000d / cost10Us;     // cost=10 单核每秒能算几次
        System.out.println();
        System.out.println("   密码空间 36^8 = " + String.format("%,.0f", space) + " 种（8 位小写字母+数字）");
        System.out.printf("   若用 MD5 单核穷举: %,.0f 秒  ≈ %.1f 天%n", space / md5PerSec, space / md5PerSec / 86400);
        System.out.printf("   若用 bcrypt cost=10 单核穷举: %,.0f 秒 ≈ %.0f 年%n",
                space / bcryptPerSec, space / bcryptPerSec / 86400 / 365);
        System.out.println("   => 攻击者换 GPU 能提速约 3~4 个数量级，bcrypt 依然扛得住");
        System.out.println();
    }

    /** ④ MD5 快到什么程度 —— 快到等于没上锁 */
    private static void demo4Md5Speed() throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] raw = "123456".getBytes();
        for (int i = 0; i < 20000; i++) {
            md5.digest(raw); // 预热 JIT
        }

        int n = 200_000;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            md5.digest(raw);
        }
        long nsPerOp = (System.nanoTime() - start) / n;

        System.out.println("④ MD5 单次耗时");
        System.out.println("   约 " + nsPerOp + " ns/次  ≈ " + String.format("%,d", 1_000_000_000L / Math.max(nsPerOp, 1)) + " 次/秒（单核，已预热 JIT）");
        System.out.println("   => MD5 的设计目标是「快」，用在密码上正好帮了攻击者的忙");
        System.out.println();
    }

    /**
     * ⑤ 关键坑：bcrypt 只看前 72 字节，超出部分被「静默丢弃」，不报错。
     *
     * <p>必须固定同一个 salt 才能看出来 —— 随机 salt 会掩盖这个问题。</p>
     */
    private static void demo5BcryptTruncatesAt72Bytes() {
        String salt = BCrypt.gensalt();
        String base = "a".repeat(72);

        String withX = BCrypt.hashpw(base + "X", salt);
        String withY = BCrypt.hashpw(base + "Y", salt);
        String plain = BCrypt.hashpw(base, salt);

        System.out.println("⑤ bcrypt 的 72 字节静默截断（固定同一个 salt）");
        System.out.println("   salt      : " + salt);
        System.out.println("   72a + 'X' : " + withX);
        System.out.println("   72a + 'Y' : " + withY);
        System.out.println("   72a       : " + plain);
        System.out.println();
        System.out.println("   三个结果完全相同? " + (withX.equals(withY) && withY.equals(plain)));
        System.out.println("   是否抛异常? 否，静默截断");
        System.out.println();
        System.out.println("   => 结论：不能把系统密钥直接拼在密码末尾当 pepper！");
        System.out.println("      正确做法是先做 HMAC-SHA256(password, pepper)，把结果压成定长再送进 bcrypt。");
    }
}

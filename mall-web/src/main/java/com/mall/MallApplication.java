package com.mall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 购物网站后端启动类
 *
 * <p>{@code @EnableScheduling} 是购物车异步落库（{@code CartFlushTask}）需要的：
 * 该方案以 Redis 为主存储，靠定时任务把"待落库"的购物车批量写回 MySQL。
 * 缺了这个注解，{@code @Scheduled} 不会生效 —— 而且<b>不会报错</b>，
 * 表现为"购物车用着一切正常，重启后数据全没了"，属于最难排查的一类问题。</p>
 */
@SpringBootApplication
@MapperScan("com.mall.mapper")
@EnableScheduling
public class MallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}

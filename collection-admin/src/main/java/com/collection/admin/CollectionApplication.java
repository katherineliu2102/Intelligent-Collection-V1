package com.collection.admin;

import java.util.TimeZone;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智能催收升级 Phase 1 — 单体启动入口（装配全部模块）。
 *
 * <p>扫描 com.collection 全包，注册引擎、渠道 Mock SPI、数据服务、数据接入等全部 Bean。 Phase 1 默认：内存事件总线 + 内存幂等（无需
 * Redis）；MySQL 新测试库观测计划/步骤/时间线落库。
 */
@SpringBootApplication(scanBasePackages = "com.collection")
@MapperScan("com.collection.service.mapper")
@EnableScheduling
public class CollectionApplication {

    /** 全系统时间口径。所有落库时间列、触达槽位与频控日窗都按此时区计算。 */
    public static final TimeZone PHT = TimeZone.getTimeZone("Asia/Manila");

    public static void main(String[] args) {
        // 必须在 Spring 起来之前定死默认时区：全仓仍有大量裸 LocalDateTime.now()（outbox 重试、
        // 停摆宽限、观察窗口），它们取 JVM 默认时区，而写进去的库列是 +08:00 会话时区。
        // 容器基础镜像默认 UTC，不设这一行就是稳定的 8 小时错位——08:00 的短信会在 16:00 发出。
        TimeZone.setDefault(PHT);
        System.setProperty("user.timezone", PHT.getID());
        SpringApplication.run(CollectionApplication.class, args);
    }
}

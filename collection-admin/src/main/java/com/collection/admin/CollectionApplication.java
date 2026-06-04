package com.collection.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 智能催收升级 Phase 1 — 单体启动入口（装配全部模块）。
 *
 * <p>扫描 com.collection 全包，注册引擎、渠道 Mock SPI、数据服务、数据接入等全部 Bean。
 * Phase 1 默认：内存事件总线 + 内存幂等（无需 Redis）；MySQL 新测试库观测计划/步骤/时间线落库。
 */
@SpringBootApplication(scanBasePackages = "com.collection")
@MapperScan("com.collection.service.mapper")
@EnableScheduling
public class CollectionApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollectionApplication.class, args);
    }
}
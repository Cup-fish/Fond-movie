package com.maoyan.provider;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 猫眼后端服务启动类
 */
@SpringBootApplication(scanBasePackages = "com.maoyan")
@MapperScan("com.maoyan.dao.mapper")
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
public class MaoyanApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaoyanApplication.class, args);
    }
}

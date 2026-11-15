package com.portfolio.worksystem;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.portfolio.worksystem.mapper")
@EnableScheduling
@SpringBootApplication
public class WorkSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkSystemApplication.class, args);
    }
}

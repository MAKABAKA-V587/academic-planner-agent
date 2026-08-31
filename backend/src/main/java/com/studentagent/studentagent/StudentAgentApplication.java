package com.studentagent.studentagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.studentagent.studentagent.mapper")
@EnableAsync
public class StudentAgentApplication {

    public static void main(String[] args) {
        // classpath 同时存在 Spring RestClient 与 JDK HttpClient 两个实现；
        // Spring RestClient 实现要求 Spring Boot 3.4+，当前 3.3.5 使用纯 JDK HttpClient
        System.setProperty("langchain4j.http.clientBuilderFactory",
                "dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory");
        SpringApplication.run(StudentAgentApplication.class, args);
    }

}

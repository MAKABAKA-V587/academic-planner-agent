package com.studentagent.studentagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.studentagent.studentagent.mapper")
@EnableAsync
@EnableScheduling
public class StudentAgentApplication {

    public static void main(String[] args) {
        // classpath 上同时存在 spring-restclient（open-ai starter 硬依赖）与 jdk client（chroma 自带），
        // langchain4j 要求显式指定 HTTP 工厂；统一选 jdk——spring-restclient 的默认请求工厂
        // 会发 HTTP/2 升级头，Chroma 0.4.24 的 uvicorn 会拒绝（400 Invalid HTTP request received）
        System.setProperty("langchain4j.http.clientBuilderFactory",
                "dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory");
        SpringApplication.run(StudentAgentApplication.class, args);
    }

}

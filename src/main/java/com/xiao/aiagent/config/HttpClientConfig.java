package com.xiao.aiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 覆盖 RestClient 的底层 HTTP 客户端：使用 JDK 的 HttpURLConnection 代替 Netty
 * 避免 Netty 异步 DNS 解析器在某些网络环境下超时的问题
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory());
    }
}
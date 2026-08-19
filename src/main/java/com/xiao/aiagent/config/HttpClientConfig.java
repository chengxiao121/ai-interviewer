package com.xiao.aiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.RestClient;

/**
 * 覆盖底层 HTTP 客户端：RestClient 与 WebClient 均改用 JDK 的 HttpURLConnection / HttpClient，
 * 替代默认的 Netty，避免 Netty 异步 DNS 解析器在某些网络环境下超时。
 * 说明：
 * - RestClient：非流式调用（普通 chat）默认走它
 * - WebClient：SSE 流式调用（.stream()）默认走它，必须一起覆盖，否则流式链路会撞 Netty DNS 超时
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory());
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .clientConnector(new JdkClientHttpConnector());
    }
}
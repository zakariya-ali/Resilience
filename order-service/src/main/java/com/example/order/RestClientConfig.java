package com.example.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The RestClient used by BOTH profiles - same base URL and same timeouts,
 * so protected vs unprotected runs are a fair comparison.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient inventoryRestClient(@Value("${inventory.base-url}") String baseUrl,
                                   @Value("${inventory.connect-timeout}") Duration connectTimeout,
                                   @Value("${inventory.read-timeout}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout); // a hung dependency becomes an error instead of blocking forever

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}

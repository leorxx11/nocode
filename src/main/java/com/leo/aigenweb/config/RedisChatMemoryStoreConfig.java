package com.leo.aigenweb.config;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
@ConfigurationProperties(prefix = "spring.data.redis")
public class RedisChatMemoryStoreConfig {

    private String host;
    private int port;
    private String password;
    private Long ttl;

    @Bean
    public RedisChatMemoryStore redisChatMemoryStore() {
        RedisChatMemoryStore.Builder builder = RedisChatMemoryStore.builder()
                .host(host)
                .port(port)
                .password(password)
                .ttl(ttl);
        // 只设置了密码、没有设置用户名时，需要显式指定 Redis 默认用户
        if (StrUtil.isNotBlank(password)) {
            builder.user("default");
        }
        return builder.build();
    }
}

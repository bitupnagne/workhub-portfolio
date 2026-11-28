package com.portfolio.worksystem.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * [S-4] Caffeine in-memory cache.
 *  - employeePresence: 3초 TTL — 우측 사이드패널/채팅 멤버 등에서 빈번하게 호출되는 무거운 쿼리.
 *  - orgTree: 60초 TTL — 조직 구조 변경 빈도가 낮은 트리.
 *
 *  TTL이 짧아 stale data 영향 최소화. 변경 이벤트(@CacheEvict)로 즉시 무효화.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                new CaffeineCache("employeePresence", Caffeine.newBuilder()
                        .expireAfterWrite(3, TimeUnit.SECONDS)
                        .maximumSize(100)
                        .build()),
                new CaffeineCache("orgTree", Caffeine.newBuilder()
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .maximumSize(10)
                        .build())
        ));
        return manager;
    }
}

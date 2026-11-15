package com.portfolio.worksystem.config;

import java.io.IOException;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.mybatis.spring.boot.autoconfigure.SqlSessionFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Structural mapper registration; connections and credentials remain external. */
@Configuration
public class MyBatisConfig {
    @Bean ConfigurationCustomizer domainMapping() {
        return configuration -> configuration.setMapUnderscoreToCamelCase(true);
    }

    @Bean SqlSessionFactoryBeanCustomizer mapperResources() throws IOException {
        var mappings = new PathMatchingResourcePatternResolver().getResources("classpath:/mapper/**/*.xml");
        return factory -> {
            factory.setTypeAliasesPackage("com.portfolio.worksystem.model");
            factory.setMapperLocations(mappings);
        };
    }
}

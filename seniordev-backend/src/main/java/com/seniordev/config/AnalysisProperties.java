package com.seniordev.config;

import com.seniordev.build.ClasspathResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnalysisProperties {

    @Bean
    public ClasspathResolver classpathResolver() {
        return new ClasspathResolver();
    }
}

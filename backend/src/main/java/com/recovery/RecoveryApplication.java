package com.recovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RecoveryApplication {
    public static void main(String[] args) { SpringApplication.run(RecoveryApplication.class, args); }

    @Bean WebMvcConfigurer cors() {
        return new WebMvcConfigurer() {
            public void addCorsMappings(CorsRegistry r) { r.addMapping("/api/**").allowedOrigins("http://localhost:5173"); }
        };
    }
}

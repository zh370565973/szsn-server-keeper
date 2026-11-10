package com.szsn.srm;

import com.szsn.srm.common.SrmProperties;
import com.szsn.srm.monitor.SseHub;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.core.io.Resource;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(SrmProperties.class)
public class SrmApplication {
    public static void main(String[] args) {
        boolean init = Arrays.asList(args).contains("init-admin");
        SpringApplicationBuilder builder = new SpringApplicationBuilder(SrmApplication.class);
        if (init) {
            builder.web(WebApplicationType.NONE).properties("srm.mode=init");
        }
        ConfigurableApplicationContext context = builder.run(args);
        if (init) {
            int code = SpringApplication.exit(context);
            System.exit(code);
        }
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SseHub sseHub() {
        return new SseHub();
    }

    @Bean
    WebMvcConfigurer spaFallback() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/**")
                        .addResourceLocations("classpath:/static/")
                        .resourceChain(true)
                        .addResolver(new PathResourceResolver() {
                            @Override
                            protected Resource getResource(String resourcePath, Resource location) throws java.io.IOException {
                                if (resourcePath.startsWith("api/")) {
                                    return null;
                                }
                                Resource requested = location.createRelative(resourcePath);
                                if (requested.exists() && requested.isReadable()) {
                                    return requested;
                                }
                                Resource index = location.createRelative("index.html");
                                if (index.exists() && index.isReadable()) {
                                    return index;
                                }
                                return null;
                            }
                        });
            }
        };
    }
}

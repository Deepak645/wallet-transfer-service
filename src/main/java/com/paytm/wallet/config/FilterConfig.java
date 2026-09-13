package com.paytm.wallet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.security.BearerAuthFilter;
import com.paytm.wallet.web.CorrelationIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        registration.setName("correlationIdFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<BearerAuthFilter> bearerAuthFilter(ObjectMapper objectMapper) {
        FilterRegistrationBean<BearerAuthFilter> registration = new FilterRegistrationBean<>(new BearerAuthFilter(objectMapper));
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        registration.setName("bearerAuthFilter");
        return registration;
    }
}

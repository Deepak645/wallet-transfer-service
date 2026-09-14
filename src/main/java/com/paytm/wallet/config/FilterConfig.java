package com.paytm.wallet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.security.BearerAuthFilter;
import com.paytm.wallet.security.TestFundingAuthFilter;
import com.paytm.wallet.web.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    /**
     * TEST INFRASTRUCTURE ONLY - guards TestFundingController. Registered
     * only when test.funding.enabled=true, mapped only to /test/* - has no
     * effect on any other endpoint. See TestFundingAuthFilter's Javadoc.
     */
    @Bean
    @ConditionalOnProperty(prefix = "test.funding", name = "enabled", havingValue = "true")
    public FilterRegistrationBean<TestFundingAuthFilter> testFundingAuthFilter(
            ObjectMapper objectMapper,
            @Value("${test.funding.token:}") String testFundingToken) {
        FilterRegistrationBean<TestFundingAuthFilter> registration =
                new FilterRegistrationBean<>(new TestFundingAuthFilter(testFundingToken, objectMapper));
        registration.addUrlPatterns("/test/*");
        registration.setOrder(3);
        registration.setName("testFundingAuthFilter");
        return registration;
    }
}

package com.coshare.patientrecord.config;

import com.coshare.patientrecord.security.AuthTokenFilter;
import com.coshare.patientrecord.security.InventoryPortalBoundaryFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<AuthTokenFilter> authTokenFilter, ObjectProvider<InventoryPortalBoundaryFilter> inventoryPortalBoundaryFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // DeferredResult 长轮询完成时会触发 ASYNC/ERROR dispatch 重新进入过滤链，
                // 此时 AuthTokenFilter(OncePerRequestFilter) 不再执行、SecurityContext 为空，
                // 必须放行 dispatch 否则 AuthorizationFilter 会以匿名身份拒绝并返回空 body 403
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/login",
                    "/home",
                    "/workbench/**",
                    "/patients/**",
                    "/system/**",
                    "/templates/**",
                    "/encounters/**",
                    "/pre-ai/**",
                    "/inventory/**",
                    "/inventory-system/**",
                    "/tcm-pharmacy/**",
                    "/favicon.ico",
                    "/assets/**",
                    "/health",
                    "/auth/login",
                    "/auth/options",
                    "/auth/options/accounts"
                ).permitAll()
                .requestMatchers(
                    "/health/db",
                    "/auth/logout",
                    "/auth/password",
                    "/auth/navigation",
                    "/clinic-api/**",
                    "/inventory-api/**"
                ).authenticated()
                .anyRequest().authenticated()
            )
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(formLogin -> formLogin.disable());
        inventoryPortalBoundaryFilter.ifAvailable(filter -> http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class));
        authTokenFilter.ifAvailable(filter -> http.addFilterAfter(filter, InventoryPortalBoundaryFilter.class));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

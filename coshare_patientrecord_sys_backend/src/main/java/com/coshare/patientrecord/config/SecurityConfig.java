package com.coshare.patientrecord.config;

import com.coshare.patientrecord.security.AuthTokenFilter;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<AuthTokenFilter> authTokenFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
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
        authTokenFilter.ifAvailable(filter -> http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

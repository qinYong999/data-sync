package com.datasync.server.security;

import com.datasync.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * 安全配置（D10）：表单登录 + 会话 + CSRF，全部 {@code /api/**} 需认证，
 * {@code /actuator/health} 匿名可读（契约 §4.3/§5.9）。
 *
 * <p>CSRF 采用"会话式存储 + 双形态校验"（见 {@link AppCsrfTokenRequestHandler}）：
 * SPA 从 {@code GET /api/auth/csrf} 取原文 token 放 {@code X-CSRF-TOKEN} 头；
 * 服务端渲染的登录表单用 Thymeleaf 自动注入的掩码 {@code _csrf}。两条路都验证。</p>
 *
 * <p>{@code app.security.enabled=false} 时整链放行并关闭 CSRF（仅本地开发）。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(AdminCredentials credentials, PasswordEncoder passwordEncoder) {
        UserDetails admin = User.withUsername(credentials.getUsername())
            .password(passwordEncoder.encode(credentials.getRawPassword()))
            .roles("ADMIN")
            .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /** JSON 登录（/api/auth/login）需要显式保存会话上下文 */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** 会话式 CSRF 存储：token 不落 cookie，避免与 XorCsrf 的原文/掩码混淆 */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AppProperties properties,
                                                   CsrfTokenRepository csrfTokenRepository) throws Exception {
        if (!properties.getSecurity().isEnabled()) {
            // 本地开发：全放行 + 关 CSRF
            http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, e) -> { }));
            return http.build();
        }

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new AppCsrfTokenRequestHandler()))
            .authorizeHttpRequests(auth -> auth
                // 探活匿名可读
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                // SPA 获取 CSRF / 会话状态 / 登录：匿名可达（登录本身仍需 CSRF 头）
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/auth/session").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                // 登录页与静态资源
                .requestMatchers("/login", "/login.html", "/favicon.ico", "/", "/index.html",
                    "/assets/**", "/static/**", "/error").permitAll()
                // 业务接口与实时日志必须登录
                .requestMatchers("/api/**", "/ws/**").authenticated()
                .anyRequest().permitAll())
            .formLogin(form -> form.loginPage("/login").permitAll())
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout").permitAll())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new ApiAuthenticationEntryPoint())
                .accessDeniedHandler(new ApiAccessDeniedHandler()))
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        log.info("安全已启用：/api/** 需认证，CSRF 使用会话式存储 + 双形态校验，/actuator/health 匿名可读");
        return http.build();
    }
}

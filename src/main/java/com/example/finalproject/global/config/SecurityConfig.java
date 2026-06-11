package com.example.finalproject.global.config;

import com.example.finalproject.auth.config.KakaoProperties;
import com.example.finalproject.auth.config.NaverProperties;
import com.example.finalproject.auth.config.OAuth2AuthorizationRequestLoggingFilter;
import com.example.finalproject.auth.config.OAuth2LoginSuccessHandler;
import com.example.finalproject.auth.service.AuthService;
import com.example.finalproject.auth.social.SocialLoginStrategyRegistry;
import com.example.finalproject.global.jwt.JwtProperties;
import com.example.finalproject.global.jwt.JwtTokenProvider;
import com.example.finalproject.global.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Profile(value = "!local")
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, KakaoProperties.class, NaverProperties.class})
public class SecurityConfig {

    @Value("${cors.allowed-origins}")
    private List<String> allowedOrigins;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SocialLoginStrategyRegistry socialLoginStrategyRegistry;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final AuthService authService;
    private final JwtProperties jwtProperties;
    private final JwtTokenProvider jwtTokenProvider;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          SocialLoginStrategyRegistry socialLoginStrategyRegistry,
                          ClientRegistrationRepository clientRegistrationRepository,
                          AuthService authService,
                          JwtProperties jwtProperties,
                          JwtTokenProvider jwtTokenProvider) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.socialLoginStrategyRegistry = socialLoginStrategyRegistry;
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.authService = authService;
        this.jwtProperties = jwtProperties;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    private OAuth2AuthorizationRequestResolver kakaoAuthorizationRequestResolver() {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(kakaoPromptLoginCustomizer());
        return resolver;
    }

    private Consumer<OAuth2AuthorizationRequest.Builder> kakaoPromptLoginCustomizer() {
        return customizer -> customizer
                .additionalParameters(params -> params.put("prompt", "login"));
    }


    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Cache-Control"));
        config.setAllowCredentials(true); // 쿠키 포함 여부

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // 카카오 OAuth 인가 요청(state)을 세션에 저장해야 해서 이 경로만 세션을 허용한다.
    // JWT로 인증하는 나머지 API 체인(apiFilterChain)까지 IF_REQUIRED를 같이 쓰면,
    // JwtAuthenticationFilter가 매 요청마다 SecurityContextHolder를 갱신할 때
    // HttpSessionSecurityContextRepository가 요청마다 새 세션을 만들어버린다.
    @Bean
    @Order(1)
    public SecurityFilterChain oauth2FilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/oauth2/**", "/login/oauth2/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(
                                SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(auth -> auth
                                .authorizationRequestResolver(
                                        kakaoAuthorizationRequestResolver()))
                        .successHandler(new OAuth2LoginSuccessHandler(
                                socialLoginStrategyRegistry,
                                authService,
                                jwtProperties,
                                jwtTokenProvider)))
                .addFilterBefore(new OAuth2AuthorizationRequestLoggingFilter(),
                        OAuth2AuthorizationRequestRedirectFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS)) // JWT라 세션 불필요. OAuth는 oauth2FilterChain에서 별도 처리
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                                    CookieUtil.clearAccessTokenCookie().toString());
                            response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                                    CookieUtil.clearRefreshTokenCookie().toString());
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET,
                                "/api/auth/check-email",
                                "/api/auth/check-phone",
                                "/api/auth/clear-cookies",
                                "/api/users/stores*",
                                "/api/products/categories",
                                "/api/products/{productId}",
                                "/api/stores/categories",
                                "/api/stores/*/products",
                                "/api/users/stores")
                        .permitAll()
                        .requestMatchers(req -> "GET".equals(req.getMethod())
                                && req.getRequestURI().matches(".*/api/stores/[0-9]+$"))
                        .permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register",
                                "/api/auth/refresh",
                                "/api/auth/login",
                                "/api/auth/logout",
                                "/api/auth/social-signup/complete",
                                "/api/auth/password-reset/request",
                                "/api/auth/password-reset/confirm",
                                "/api/auth/send-verification",
                                "/api/auth/verify-phone",
                                "/api/payments/webhooks/toss")
                        .permitAll()
                        .requestMatchers(
                                "/error",
                                "/api/notices",
                                "/api/banners")
                        .permitAll()
                        .requestMatchers(
                                "/api/admin/notices/**",
                                "/api/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers(
                                "/api/riders/register",
                                "/api/riders/approvals/*")
                        .hasRole("CUSTOMER")
                        // ── RIDER (GET) ──
                        .requestMatchers(HttpMethod.GET,
                                "/api/riders",
                                "/api/riders/locations/{riderId}",
                                "/api/riders/deliveries/**")
                        .hasRole("RIDER")
                        // ── RIDER (POST) ──
                        .requestMatchers(HttpMethod.POST,
                                "/api/riders/locations",
                                "/api/riders/deliveries/*/accept",
                                "/api/storage/delivery/**")
                        .hasRole("RIDER")
                        // ── RIDER (PATCH) ──
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/riders/status",
                                "/api/riders/deliveries/*/pickup",
                                "/api/riders/deliveries/*/start",
                                "/api/riders/deliveries/*/complete")
                        .hasRole("RIDER")
                        // ── RIDER (DELETE) ──
                        .requestMatchers(HttpMethod.DELETE,
                                "/api/riders/locations/{riderId}")
                        .hasRole("RIDER")
                        .requestMatchers(
                                "/api/store/orders/**",
                                "/api/store/settlements",
                                "/api/store/settlements/**",
                                "/api/store/subscriptions",
                                "/api/store/subscriptions/**",
                                "/api/store/subscription-products",
                                "/api/store/subscription-products/**")
                        .hasRole("STORE")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

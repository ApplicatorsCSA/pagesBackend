package com.open.spring.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Autowired
    private JwtRequestFilter jwtRequestFilter;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {

        http
            .securityMatcher("/api/**", "/authenticate")
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())

            .authorizeHttpRequests(auth -> auth

                // ========= CORS =========
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // ========= AUTH =========
                .requestMatchers(HttpMethod.POST, "/authenticate").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/person/create").permitAll()

                // ========= ASSIGNMENTS (OPEN – NO AUTH) =========
                .requestMatchers("/api/assignments/**").permitAll()
                .requestMatchers("/api/assignment-submissions/**").permitAll()
                // ================================================

                // ========= PERSON =========
                .requestMatchers(HttpMethod.DELETE, "/api/person/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/person/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/person/uid/**")
                    .hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")

                // ========= PUBLIC =========
                .requestMatchers("/api/jokes/**").permitAll()
                .requestMatchers("/api/pausemenu/**").permitAll()
                .requestMatchers("/api/leaderboard/**").permitAll()
                .requestMatchers("/api/gamer/**").permitAll()
                .requestMatchers("/api/analytics/**").permitAll()
                .requestMatchers("/api/plant/**").permitAll()
                .requestMatchers("/api/groups/**").permitAll()
                .requestMatchers("/api/grade-prediction/**").permitAll()
                .requestMatchers("/api/admin-evaluation/**").permitAll()
                .requestMatchers("/api/grades/**").permitAll()
                .requestMatchers("/api/progress/**").permitAll()
                .requestMatchers("/api/calendar/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/sprint-dates/**").permitAll()

                // ========= CONTENT =========
                .requestMatchers("/api/content/**")
                    .hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers("/api/collections/**")
                    .hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers("/api/events/**")
                    .hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")

                // ========= SYNERGY =========
                .requestMatchers(HttpMethod.POST, "/api/synergy/grades/requests")
                    .hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/synergy/saigai/")
                    .hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/synergy/**")
                    .hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")

                // ========= AI / TOOLS =========
                .requestMatchers(HttpMethod.POST, "/api/upai").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/upai/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/gemini-frq/grade").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/gemini-frq/grade/**").permitAll()

                // ========= QUESTS / CERTS =========
                .requestMatchers("/api/quests/**")
                    .hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers("/api/certificates/**")
                    .hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers("/api/user-certificates/**")
                    .hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")

                // ========= CHALLENGES =========
                .requestMatchers(HttpMethod.POST, "/api/challenge-submission/**")
                    .authenticated()

                // ========= ANALYTICS =========
                .requestMatchers("/api/ocs-analytics/**").authenticated()

                // ========= DEFAULT =========
                .requestMatchers("/api/**").authenticated()
            )

            .exceptionHandling(ex ->
                ex.authenticationEntryPoint(jwtAuthenticationEntryPoint)
            )

            .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, JwtRequestFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowCredentials(true);
        configuration.addAllowedOriginPattern("http://localhost:4500");
        configuration.addAllowedOriginPattern("https://opencodingsociety.com");
        configuration.addAllowedOriginPattern("http://opencodingsociety.com");
        configuration.addAllowedOriginPattern("https://pages.opencodingsociety.com");
        configuration.addAllowedOriginPattern("https://spring.opencodingsociety.com");
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

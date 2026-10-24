package com.simplematch.queryservice.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Owns Query HTTP authentication and explicit route policy independently of FIX and rebuild. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(QuerySecurityProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QueryHttpSecurityConfiguration {
  /** Creates the single startup-validated immutable policy used for both identity and grants. */
  @Bean
  QuerySecurityPolicy querySecurityPolicy(
      QuerySecurityProperties properties, Clock queryServiceClock) {
    return new QuerySecurityPolicy(properties, queryServiceClock);
  }

  /**
   * Establishes registered Query identity without creating framework-generated user credentials.
   */
  @Bean
  AuthenticationManager queryAuthenticationManager(QuerySecurityPolicy policy) {
    return authentication -> {
      if (!(authentication.getCredentials() instanceof String token)) {
        throw new BadCredentialsException("Query credential is invalid");
      }
      final QueryPrincipal principal = policy.authenticate(token);
      final var authorities =
          principal.operationsRead()
              ? List.of(new SimpleGrantedAuthority(QueryPrincipal.OPERATIONS_READ))
              : List.<SimpleGrantedAuthority>of();
      return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    };
  }

  /** Uses HTTP Bearer challenges without claiming the provisioned credential is OAuth-issued. */
  @Bean
  AuthenticationEntryPoint queryAuthenticationEntryPoint() {
    return (request, response, failure) -> {
      final String challenge =
          failure instanceof BadCredentialsException
              ? "Bearer realm=\"query\", error=\"invalid_token\""
              : "Bearer realm=\"query\"";
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    };
  }

  /**
   * Allows only reviewed routes; all accepted authentication uses explicit headers, never cookies.
   * CSRF is disabled for this single stateless chain after reviewing both Query and operator paths.
   */
  @Bean
  SecurityFilterChain queryHttpSecurityFilterChain(
      HttpSecurity http,
      AuthenticationManager queryAuthenticationManager,
      AuthenticationEntryPoint queryAuthenticationEntryPoint)
      throws Exception {
    final var paths = PathPatternRequestMatcher.withDefaults();
    final RequestMatcher rebuild = paths.matcher(HttpMethod.POST, "/internal/query/rebuild");
    final RequestMatcher probes =
        new OrRequestMatcher(
            paths.matcher(HttpMethod.GET, "/actuator/health/liveness"),
            paths.matcher(HttpMethod.GET, "/actuator/health/readiness"));
    final AuthenticationFilter bearer =
        new AuthenticationFilter(
            queryAuthenticationManager, new QueryBearerAuthenticationConverter());
    bearer.setRequestMatcher(new NegatedRequestMatcher(new OrRequestMatcher(rebuild, probes)));
    bearer.setSecurityContextRepository(new NullSecurityContextRepository());
    bearer.setFailureHandler(
        new AuthenticationEntryPointFailureHandler(queryAuthenticationEntryPoint));
    bearer.setSuccessHandler(
        (request, response, authentication) -> {
          if (!(authentication.getPrincipal() instanceof QueryPrincipal principal)) {
            throw new BadCredentialsException("Query principal is invalid");
          }
          response.setHeader(
              "X-SimpleMatch-Query-Policy-Version", Long.toString(principal.policyVersion()));
        });
    return http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .securityContext(
            context -> context.securityContextRepository(new NullSecurityContextRepository()))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(queryAuthenticationEntryPoint)
                    .accessDeniedHandler(
                        (request, response, denied) ->
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
        .addFilterBefore(bearer, AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(
            routes ->
                routes
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(probes, rebuild)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/freshness")
                    .hasAuthority(QueryPrincipal.OPERATIONS_READ)
                    .requestMatchers(HttpMethod.GET, "/api/v1/accounts/{accountId}/summary")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/orders/{orderId}")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/orders/{orderId}/executions")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET,
                        "/api/v1/market-reference/{tradingDay}/{venueMic}/{symbol}")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .build();
  }
}

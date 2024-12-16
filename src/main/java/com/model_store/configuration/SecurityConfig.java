//package com.model_store.configuration;
//
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
//import org.springframework.security.config.web.server.ServerHttpSecurity;
//import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
//import org.springframework.security.core.userdetails.User;
//import org.springframework.security.core.userdetails.UserDetails;
//import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
//import org.springframework.security.crypto.password.PasswordEncoder;
//import org.springframework.security.web.server.SecurityWebFilterChain;
//
//@Configuration
////@EnableWebFluxSecurity
//public class SecurityConfig {


//@EnableWebSecurity
//public class SecurityConfig extends WebSecurityConfigurerAdapter {
//    @Override
//    protected void configure(HttpSecurity http) throws Exception {
//        http.csrf().disable()
//                .authorizeRequests()
//                .antMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
//                .anyRequest().authenticated();
//    }
//}


//
////    @Bean
////    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
////        return http.authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
////                .formLogin(formLogin -> formLogin.loginPage("/login"))
////                .build();
////    }
//
//    @Bean
//    public MapReactiveUserDetailsService userDetailsService() {
//
//        UserDetails user = User
//                .withUsername("user")
//                .password(passwordEncoder().encode("password"))
//                .roles("USER")
//                .build();
//        return new MapReactiveUserDetailsService(user);
//    }
//
//    @Bean
//    public PasswordEncoder passwordEncoder() {
//        return new BCryptPasswordEncoder();
//    }
//}

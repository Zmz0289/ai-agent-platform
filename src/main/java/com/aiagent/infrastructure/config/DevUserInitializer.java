package com.aiagent.infrastructure.config;

import com.aiagent.auth.domain.User;
import com.aiagent.auth.infrastructure.repository.UserRepository;
import com.aiagent.infrastructure.security.DevAuthFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 学习模式下保证固定账号存在，供会话/消息表的外键关联使用。
 *
 * <p>仅在 {@code ai.security.auth-enabled=false} 时启用；账号已存在但被禁用时会重新启用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.security", name = "auth-enabled", havingValue = "false")
public class DevUserInitializer implements ApplicationRunner {

    private static final String DEV_PASSWORD = "demo12345";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        userRepository.findByUsername(DevAuthFilter.DEV_USERNAME).ifPresentOrElse(user -> {
            if (!Boolean.TRUE.equals(user.getEnabled())) {
                user.setEnabled(true);
                userRepository.save(user);
                log.info("Enabled dev user for anonymous access: {}", DevAuthFilter.DEV_USERNAME);
            }
        }, () -> {
            User user = User.builder()
                    .username(DevAuthFilter.DEV_USERNAME)
                    .password(passwordEncoder.encode(DEV_PASSWORD))
                    .email("demo@localhost")
                    .enabled(true)
                    .build();
            userRepository.save(user);
            log.info("Created dev user for anonymous access: {} (password: {})",
                    DevAuthFilter.DEV_USERNAME, DEV_PASSWORD);
        });
    }
}

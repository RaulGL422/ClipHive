package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.model.AdminUser;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the single superadmin account on first boot from
 * {@code CLIPHIVE_ADMIN_USER} / {@code CLIPHIVE_ADMIN_PASSWORD}
 * (docs/DESIGN.md §12.1). Idempotent: if a row already exists it does nothing.
 *
 * <p>There is no registration endpoint — this is the only way to create the account.
 * The 2FA secret is configured separately from the superadmin web UI.
 */
@Component
public class AdminUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminUserInitializer.class);

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final ClipHiveProperties props;

    public AdminUserInitializer(AdminUserRepository adminUserRepository,
                                PasswordEncoder passwordEncoder,
                                ClipHiveProperties props) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminUserRepository.count() > 0) {
            return;
        }
        String username = props.getAdminUser();
        String password = props.getAdminPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("CLIPHIVE_ADMIN_USER or CLIPHIVE_ADMIN_PASSWORD is not set — "
                    + "superadmin account was NOT created. Set both and restart.");
            return;
        }
        var user = new AdminUser(username, passwordEncoder.encode(password));
        adminUserRepository.save(user);
        log.info("Superadmin account '{}' created (2FA not yet enabled — configure from the web UI)",
                username);
    }
}

package io.github.raulgl422.cliphive.server.security;

import io.github.raulgl422.cliphive.server.repository.AdminUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads the single admin account for Spring Security form login.
 * Returns {@code ROLE_ADMIN_PRE_AUTH} so that the TOTP step (if enabled) can
 * gate the upgrade to {@code ROLE_ADMIN} — see {@link AdminSecurityConfig}.
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository adminUserRepository;

    public AdminUserDetailsService(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var admin = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown admin user: " + username));
        return new User(admin.getUsername(), admin.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN_PRE_AUTH")));
    }
}

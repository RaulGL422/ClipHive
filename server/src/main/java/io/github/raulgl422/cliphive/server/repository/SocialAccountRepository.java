package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.SocialAccount;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, UUID> {

    List<SocialAccount> findByPlatform(Platform platform);

    Optional<SocialAccount> findByPlatformAndExternalAccountId(Platform platform, String externalAccountId);
}

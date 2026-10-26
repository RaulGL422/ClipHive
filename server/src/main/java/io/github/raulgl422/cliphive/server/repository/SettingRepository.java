package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<Setting, String> {
}

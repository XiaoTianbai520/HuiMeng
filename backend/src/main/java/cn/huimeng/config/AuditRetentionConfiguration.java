package cn.huimeng.config;

import cn.huimeng.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

/** Deletes only expired audit metadata; no user endpoint can edit audit history. */
@Configuration
@EnableScheduling
public class AuditRetentionConfiguration {
  private final AuditService audit;
  private final int days;

  public AuditRetentionConfiguration(
      AuditService audit, @Value("${huimeng.audit-retention-days:180}") int days) {
    if (days < 1) throw new IllegalArgumentException("Audit retention must be positive");
    this.audit = audit;
    this.days = days;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
  public void purgeExpired() {
    audit.purgeExpired(days);
  }
}

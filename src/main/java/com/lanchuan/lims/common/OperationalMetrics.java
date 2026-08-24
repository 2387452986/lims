package com.lanchuan.lims.common;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.OffsetDateTime;

@Component
public class OperationalMetrics {
    private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);
    private static final double MISSING_AGE_SECONDS = Duration.ofDays(3650).toSeconds();
    private final JdbcClient jdbc;
    private final Path fileRoot;
    private final Path certificate;

    public OperationalMetrics(JdbcClient jdbc, MeterRegistry registry,
                              @Value("${lims.files.root}") String fileRoot,
                              @Value("${lims.monitoring.tls-certificate:}") String certificate) {
        this.jdbc = jdbc;
        this.fileRoot = Path.of(fileRoot);
        this.certificate = certificate == null || certificate.isBlank() ? null : Path.of(certificate);
        Gauge.builder("lims.background.jobs.ready", this, metrics -> metrics.countJobs("READY", "RETRY"))
                .description("Ready or retrying PostgreSQL worker jobs").register(registry);
        Gauge.builder("lims.background.jobs.failed", this, metrics -> metrics.countJobs("FAILED"))
                .description("Failed PostgreSQL worker jobs").register(registry);
        Gauge.builder("lims.security.locked.accounts", this, OperationalMetrics::lockedAccounts)
                .description("Accounts currently locked after failed authentication").register(registry);
        Gauge.builder("lims.backup.age.seconds", this, OperationalMetrics::backupAgeSeconds)
                .description("Seconds since the last paired database and file recovery point").register(registry);
        Gauge.builder("lims.tls.certificate.expiry.seconds", this, OperationalMetrics::certificateExpirySeconds)
                .description("Seconds until the configured internal TLS certificate expires").register(registry);
        Gauge.builder("lims.file.store.free.bytes", this, OperationalMetrics::fileStoreFreeBytes)
                .description("Free bytes on the immutable file store").register(registry);
    }

    private double countJobs(String... states) {
        String clause = String.join("','", states);
        return query("SELECT count(*) FROM background_job WHERE state IN ('" + clause + "')");
    }

    private double lockedAccounts() {
        return query("SELECT count(*) FROM app_user WHERE locked_until>now()");
    }

    private double backupAgeSeconds() {
        try {
            OffsetDateTime recoveryPoint = jdbc.sql("SELECT occurred_at FROM operational_marker WHERE marker_key='LAST_PAIRED_BACKUP'")
                    .query(OffsetDateTime.class).optional().orElse(null);
            return recoveryPoint == null ? MISSING_AGE_SECONDS
                    : Math.max(0, Duration.between(recoveryPoint, OffsetDateTime.now()).toSeconds());
        } catch (RuntimeException exception) {
            log.debug("Backup age metric unavailable", exception);
            return MISSING_AGE_SECONDS;
        }
    }

    private double certificateExpirySeconds() {
        if (certificate == null || !Files.isRegularFile(certificate)) return MISSING_AGE_SECONDS;
        try (InputStream input = Files.newInputStream(certificate)) {
            X509Certificate value = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            return Math.max(0, Duration.between(java.time.Instant.now(), value.getNotAfter().toInstant()).toSeconds());
        } catch (Exception exception) {
            log.warn("TLS certificate expiry metric unavailable", exception);
            return 0;
        }
    }

    private double fileStoreFreeBytes() {
        try {
            Files.createDirectories(fileRoot);
            return Files.getFileStore(fileRoot).getUsableSpace();
        } catch (Exception exception) {
            log.warn("File store capacity metric unavailable", exception);
            return 0;
        }
    }

    private double query(String sql) {
        try { return jdbc.sql(sql).query(Long.class).single(); }
        catch (RuntimeException exception) { log.debug("Operational database metric unavailable", exception); return Double.NaN; }
    }
}

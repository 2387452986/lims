package com.lanchuan.lims.configuration;

import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Service
public class NumberingService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final NumberPatternRenderer renderer;

    @Autowired
    public NumberingService(JdbcClient jdbc, CurrentActor actor, NumberPatternRenderer renderer) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.renderer = renderer;
    }

    /** PostgreSQL concurrency tests use the compatibility path without a request principal. */
    public NumberingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.actor = null;
        this.renderer = new NumberPatternRenderer();
    }

    @Transactional
    public String next(String ruleCode, String prefix) {
        ConfiguredRule configured = configured(ruleCode);
        if (configured != null) {
            long value = jdbc.sql("""
                    INSERT INTO business_number_sequence(rule_series_id,current_value)
                    VALUES (:series,:initial)
                    ON CONFLICT(rule_series_id) DO UPDATE SET
                      current_value=business_number_sequence.current_value+1,
                      lock_version=business_number_sequence.lock_version+1,updated_at=now()
                    RETURNING current_value
                    """).param("series", configured.seriesId()).param("initial", configured.initialSequence())
                    .query(Long.class).single();
            return renderer.render(BusinessNumberingConfigurationService.pattern(configured.prefix(),
                    configured.prefixPosition(), configured.datePattern(), configured.suffix(),
                    configured.suffixPosition(), configured.sequenceWidth()), "", LocalDate.now(), value);
        }
        String period = String.valueOf(LocalDate.now().getYear());
        long value = jdbc.sql("""
                INSERT INTO number_sequence(rule_code, period_key, current_value) VALUES (:rule, :period, 1)
                ON CONFLICT(rule_code, period_key) DO UPDATE SET current_value=number_sequence.current_value+1,
                  updated_at=now() RETURNING current_value
                """).param("rule", ruleCode).param("period", period).query(Long.class).single();
        return "%s-%s-%05d".formatted(prefix, period, value);
    }

    private ConfiguredRule configured(String ruleCode) {
        if (actor == null || ruleCode == null) return null;
        String resource = ruleCode.trim().toUpperCase();
        if (!Set.of("PROJECT", "CONTRACT", "QUALITY_ASSESSMENT", "PRECOMMISSION", "COMMISSION").contains(resource)) {
            return null;
        }
        UUID organization = actor.require().organizationId();
        return jdbc.sql("""
                SELECT series_id,prefix,prefix_position,date_pattern,suffix,suffix_position,
                  sequence_width,initial_sequence
                FROM business_numbering_rule
                WHERE organization_id=:organization AND resource_type=:resource AND state='PUBLISHED'
                """).param("organization", organization).param("resource", resource)
                .query((rs, row) -> new ConfiguredRule(rs.getObject("series_id", UUID.class),
                        rs.getString("prefix"), rs.getString("prefix_position"), rs.getString("date_pattern"),
                        rs.getString("suffix"), rs.getString("suffix_position"), rs.getInt("sequence_width"),
                        rs.getLong("initial_sequence"))).optional().orElse(null);
    }

    private record ConfiguredRule(UUID seriesId, String prefix, String prefixPosition, String datePattern,
                                  String suffix, String suffixPosition, int sequenceWidth, long initialSequence) {}
}

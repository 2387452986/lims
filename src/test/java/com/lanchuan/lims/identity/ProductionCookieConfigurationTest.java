package com.lanchuan.lims.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionCookieConfigurationTest {
    @Test
    void productionKeepsSecureCookiesByDefaultButAllowsAnExplicitHttpLanOverride() throws IOException {
        assertThat(cookieSecure(Map.of())).isTrue();
        assertThat(cookieSecure(Map.of("COOKIE_SECURE", "false"))).isFalse();
    }

    private boolean cookieSecure(Map<String, Object> overrides) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application-prod", new ClassPathResource("application-prod.yml"))
                .forEach(sources::addLast);
        if (!overrides.isEmpty()) sources.addFirst(new MapPropertySource("test-overrides", overrides));
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("server.servlet.session.cookie.secure", Boolean.class)
                .orElseThrow(() -> new AssertionError("production session cookie security must be configured"));
    }
}

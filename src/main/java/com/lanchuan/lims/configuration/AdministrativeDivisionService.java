package com.lanchuan.lims.configuration;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
public class AdministrativeDivisionService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private volatile Cached cached;

    public AdministrativeDivisionService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Node(String name, List<Node> children) {}
    public record Payload(int schemaVersion, String sourceSystem, String sourcePath, String sourceUrl,
                          String sourceCapturedAt, boolean sourceRecordIdentifiersExposed,
                          String sourceIdentifierKind, Map<String, Integer> counts,
                          String captureSha256, List<Node> items) {}
    public record Option(String name, int sortOrder, boolean hasChildren) {}
    private record Published(UUID id, int versionNumber) {}
    private record Cached(UUID id, int versionNumber, List<Node> items) {}

    public List<Option> children(String province, String city) {
        List<Node> nodes;
        if (blank(province)) {
            if (!blank(city)) throw invalid("选择市级行政区前必须先选择省级行政区");
            nodes = current().items();
        } else {
            Node provinceNode = require(current().items(), province, "省级行政区");
            nodes = blank(city) ? children(provinceNode) : children(require(children(provinceNode), city, "市级行政区"));
        }
        return IntStream.range(0, nodes.size())
                .mapToObj(index -> new Option(nodes.get(index).name(), index, !children(nodes.get(index)).isEmpty()))
                .toList();
    }

    public void validate(String province, String city, String district) {
        if (blank(province)) {
            if (!blank(city) || !blank(district)) throw invalid("市级或区县行政区不能脱离省级行政区保存");
            return;
        }
        Node provinceNode = require(current().items(), province, "省级行政区");
        if (blank(city)) {
            if (!blank(district)) throw invalid("区县行政区不能脱离市级行政区保存");
            return;
        }
        Node cityNode = require(children(provinceNode), city, "市级行政区");
        if (!blank(district)) require(children(cityNode), district, "区县行政区");
    }

    private Cached current() {
        Published published = jdbc.sql("""
                SELECT id, version_number FROM configuration_version
                WHERE configuration_type='DICTIONARY' AND code='ADMINISTRATIVE_DIVISION' AND state='PUBLISHED'
                ORDER BY version_number DESC,id DESC LIMIT 1
                """).query((rs, row) -> new Published(rs.getObject("id", UUID.class),
                        rs.getInt("version_number"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "ADMINISTRATIVE_DIVISION_NOT_PUBLISHED", "行政区划配置尚未发布"));
        Cached snapshot = cached;
        if (snapshot != null && snapshot.id().equals(published.id())
                && snapshot.versionNumber() == published.versionNumber()) return snapshot;
        synchronized (this) {
            snapshot = cached;
            if (snapshot != null && snapshot.id().equals(published.id())
                    && snapshot.versionNumber() == published.versionNumber()) return snapshot;
            try {
                String content = jdbc.sql("SELECT content_json::text FROM configuration_version WHERE id=:id")
                        .param("id", published.id()).query(String.class).single();
                Payload payload = json.readValue(content, Payload.class);
                if (payload.items() == null || payload.items().isEmpty()) {
                    throw new IllegalStateException("Published administrative division configuration is empty");
                }
                cached = new Cached(published.id(), published.versionNumber(), List.copyOf(payload.items()));
                return cached;
            } catch (JacksonException exception) {
                throw new IllegalStateException("Published administrative division configuration is invalid", exception);
            }
        }
    }

    private Node require(List<Node> nodes, String name, String label) {
        String expected = name == null ? "" : name.trim();
        return nodes.stream().filter(node -> node.name().equals(expected)).findFirst()
                .orElseThrow(() -> invalid(label + "不属于当前已发布行政区划"));
    }

    private List<Node> children(Node node) { return node.children() == null ? List.of() : node.children(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ADMINISTRATIVE_DIVISION_INVALID", message);
    }
}

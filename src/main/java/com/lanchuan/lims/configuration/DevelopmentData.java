package com.lanchuan.lims.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Component
@Profile("dev")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DevelopmentData implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final String password;

    public DevelopmentData(JdbcClient jdbc, PasswordEncoder encoder,
                           @Value("${DEV_SEED_PASSWORD:}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UUID admin = jdbc.sql("SELECT id FROM app_user ORDER BY created_at LIMIT 1").query(UUID.class).optional().orElse(null);
        if (admin == null) return;
        seedPeople(admin);
        if (password != null && !password.isBlank()) seedRoleUsers();
        seedBusiness(admin);
        backfillSeedDetectionSnapshots();
    }

    private void seedPeople(UUID admin) {
        jdbc.sql("""
                INSERT INTO person(id, department_id, employee_number, name, title) VALUES
                ('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-000000000011','JC001','顾明远','检测工程师'),
                ('00000000-0000-0000-0000-000000000202','00000000-0000-0000-0000-000000000011','JC002','周知行','高级工程师'),
                ('00000000-0000-0000-0000-000000000203','00000000-0000-0000-0000-000000000011','JC003','沈砚清','正高级工程师'),
                ('00000000-0000-0000-0000-000000000204','00000000-0000-0000-0000-000000000011','JC004','叶安然','质量负责人')
                ON CONFLICT(employee_number) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO person_qualification(id, person_id, qualification_code, certificate_number,
                  valid_from, valid_until, status)
                VALUES ('00000000-0000-0000-0000-000000000211','00000000-0000-0000-0000-000000000201',
                  'CONCRETE_COMPRESSIVE','LC-JC-2026-001',current_date-interval '1 year',current_date+interval '2 years','VALID')
                ON CONFLICT(person_id, qualification_code, certificate_number) DO NOTHING
                """).update();
        // The isolated role-flow profile owns the quality person through the dedicated `quality`
        // account. Only attach that person to the administrator when role users are not enabled.
        if (password == null || password.isBlank()) {
            jdbc.sql("UPDATE app_user SET person_id='00000000-0000-0000-0000-000000000204' WHERE id=:id AND person_id IS NULL")
                    .param("id", admin).update();
        }
    }

    private void seedRoleUsers() {
        seedUser("tester", "顾明远", "00000000-0000-0000-0000-000000000201", "00000000-0000-0000-0000-000000000103");
        seedUser("reviewer", "周知行", "00000000-0000-0000-0000-000000000202", "00000000-0000-0000-0000-000000000104");
        seedUser("issuer", "沈砚清", "00000000-0000-0000-0000-000000000203", "00000000-0000-0000-0000-000000000105");
        seedUser("quality", "叶安然", "00000000-0000-0000-0000-000000000204", "00000000-0000-0000-0000-000000000102");
    }

    private void seedUser(String username, String displayName, String personId, String roleId) {
        UUID id = jdbc.sql("SELECT id FROM app_user WHERE username=:username").param("username", username)
                .query(UUID.class).optional().orElseGet(() -> {
                    UUID created = UUID.randomUUID();
                    jdbc.sql("""
                            INSERT INTO app_user(id, person_id, organization_id, department_id, username,
                              password_hash, display_name, must_change_password)
                            VALUES (:id, CAST(:person AS uuid), '00000000-0000-0000-0000-000000000001',
                              '00000000-0000-0000-0000-000000000011', :username, :hash, :displayName, false)
                            """).param("id", created).param("person", personId).param("username", username)
                            .param("hash", encoder.encode(password)).param("displayName", displayName).update();
                    return created;
                });
        jdbc.sql("INSERT INTO user_role(user_id, role_id) VALUES (:user, CAST(:role AS uuid)) ON CONFLICT DO NOTHING")
                .param("user", id).param("role", roleId).update();
    }

    private void seedBusiness(UUID admin) {
        jdbc.sql("""
                INSERT INTO customer(id, organization_id, code, name, short_name, unit_type, commissioning_unit,
                  unified_credit_code, contact_name, contact_phone)
                VALUES ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000001',
                  'KH-2026-001','澜川工程建设有限公司','澜川建设','ENTRUSTING',true,
                  '91300000SHOWCASE01','韩工','13800000000')
                ON CONFLICT(organization_id, code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO project(id, organization_id, customer_id, number, name, location, responsible_person,
                  status, created_by)
                VALUES ('00000000-0000-0000-0000-000000000401','00000000-0000-0000-0000-000000000001',
                  '00000000-0000-0000-0000-000000000301','GC-2026-0142','澜川水库除险加固工程',
                  '澜川市青湖区','郑工','ACTIVE',:actor)
                ON CONFLICT(organization_id, number) DO UPDATE SET
                  name=excluded.name,location=excluded.location,responsible_person=excluded.responsible_person,
                  updated_at=now()
                """).param("actor", admin).update();
        jdbc.sql("""
                INSERT INTO commission(id, organization_id, project_id, customer_id, number, contact_name,
                  contact_phone, commissioned_on, due_on, state, created_by)
                VALUES ('00000000-0000-0000-0000-000000000501','00000000-0000-0000-0000-000000000001',
                  '00000000-0000-0000-0000-000000000401','00000000-0000-0000-0000-000000000301',
                  'WT-2026-0086','韩工','13800000000',current_date-interval '8 days',current_date+interval '12 days','TESTING',:actor)
                ON CONFLICT(number) DO NOTHING
                """).param("actor", admin).update();
        jdbc.sql("""
                INSERT INTO sample(id, commission_id, number, name, specification, received_quantity,
                  available_quantity, unit, received_at, state)
                VALUES ('00000000-0000-0000-0000-000000000601','00000000-0000-0000-0000-000000000501',
                  'YP-2026-0159','混凝土立方体试件','150×150×150mm',12,8,'组',now()-interval '6 days','IN_TEST')
                ON CONFLICT(number) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO test_parameter(id, code, name, version_number, value_type, definition_json, formula, unit,
                  status, published_at, project_revision_id, source_system, source_key, source_occurrence, sort_order)
                VALUES ('00000000-0000-0000-0000-000000000701','CONCRETE-COMPRESSIVE','混凝土抗压强度',1,'NUMBER',
                  '{"qualificationCode":"CONCRETE_COMPRESSIVE","fields":[{"key":"load","type":"number","required":true},{"key":"area","type":"number","required":true}]}'::jsonb,
                  'load / area','MPa','PUBLISHED',now()-interval '1 year',
                  '00000000-0000-0000-0000-00000000d004','lims','development:concrete-compressive',1,1)
                ON CONFLICT(code, version_number) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO equipment(id, organization_id, code, name, model, serial_number, location, custodian_id, state)
                VALUES ('00000000-0000-0000-0000-000000000901','00000000-0000-0000-0000-000000000001',
                  'SB-0231','微机控制电液伺服压力试验机','YAW-2000','DEV-2026-0231','力学试验室',
                  '00000000-0000-0000-0000-000000000201','AVAILABLE') ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO equipment_certificate(id, equipment_id, certificate_type, certificate_number,
                  issued_on, valid_until, result)
                VALUES ('00000000-0000-0000-0000-000000000902','00000000-0000-0000-0000-000000000901',
                  'VERIFICATION','LC-JD-2026-231',current_date-interval '3 months',current_date+interval '9 months','PASS')
                ON CONFLICT(equipment_id, certificate_type, certificate_number) DO NOTHING
                """).update();
        for (int i = 1; i <= 5; i++) {
            UUID taskId = UUID.fromString("00000000-0000-0000-0000-%012d".formatted(800 + i));
            String state = i <= 3 ? "COMPLETED" : i == 4 ? "IN_PROGRESS" : "ACCEPTED";
            jdbc.sql("""
                    INSERT INTO test_task(id, commission_id, sample_id, parameter_id, number, assigned_to,
                      assigned_by, due_at, state)
                    VALUES (:id,'00000000-0000-0000-0000-000000000501','00000000-0000-0000-0000-000000000601',
                      '00000000-0000-0000-0000-000000000701',:number,'00000000-0000-0000-0000-000000000201',
                      :actor,now()+(:days || ' days')::interval,:state) ON CONFLICT(number) DO NOTHING
                    """).param("id", taskId).param("number", "RW-2026-%04d".formatted(125 + i))
                    .param("actor", admin).param("days", i).param("state", state).update();
            if (i <= 3) {
                UUID recordId = UUID.fromString("00000000-0000-0000-0000-%012d".formatted(1000 + i));
                jdbc.sql("""
                        INSERT INTO test_record(id, task_id, revision, values_json, result, state, recorded_by,
                          equipment_id, submitted_at, confirmed_at)
                        VALUES (:id,:task,0,'{"load":"812.4","area":"22500","calculatedResult":"36.1067"}'::jsonb,
                          'CONFORMING','CONFIRMED','00000000-0000-0000-0000-000000000201',
                          '00000000-0000-0000-0000-000000000901',now()-interval '2 days',now()-interval '1 day')
                        ON CONFLICT(task_id, revision) DO NOTHING
                        """).param("id", recordId).param("task", taskId).update();
            }
        }
        jdbc.sql("""
                INSERT INTO notification(id, recipient_id, organization_id, category, title, body, resource_type, resource_id)
                VALUES ('00000000-0000-0000-0000-000000001101',:actor,
                  '00000000-0000-0000-0000-000000000001','TASK','检测任务临近完成期限','2项检测任务将在48小时内到期','COMMISSION',
                  '00000000-0000-0000-0000-000000000501')
                ON CONFLICT(id) DO NOTHING
                """).param("actor", admin).update();
    }

    /**
     * Development fixtures are inserted after Flyway has finished. Keep them on the same immutable
     * selection contract that V12 applies to databases which already contained business records.
     */
    private void backfillSeedDetectionSnapshots() {
        jdbc.sql("""
                INSERT INTO commission_detection_selection(
                  id, commission_id, project_revision_id, project_name_snapshot, project_symbol_snapshot,
                  revision_number_snapshot, sort_order, created_at)
                SELECT md5('legacy-detection-selection:' || c.id::text)::uuid, c.id,
                  '00000000-0000-0000-0000-00000000d004', '既有平铺参数', NULL, 1, 1, c.created_at
                FROM commission c
                WHERE c.id='00000000-0000-0000-0000-000000000501'
                ON CONFLICT (commission_id, project_revision_id) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO commission_sample_group(
                  id, commission_id, client_key, name, description, quantity, unit, sort_order, snapshot_json)
                SELECT md5('legacy-sample-group:' || s.id::text)::uuid, s.commission_id,
                  'legacy:' || s.id::text, s.name, s.specification, s.received_quantity, s.unit, 1,
                  jsonb_build_object('source','development-fixture','sampleId',s.id,'sampleNumber',s.number,'state',s.state)
                FROM sample s
                WHERE s.id='00000000-0000-0000-0000-000000000601'
                ON CONFLICT (commission_id, client_key) DO NOTHING
                """).update();
        jdbc.sql("""
                UPDATE sample
                SET sample_group_id=md5('legacy-sample-group:' || id::text)::uuid
                WHERE id='00000000-0000-0000-0000-000000000601' AND sample_group_id IS NULL
                """).update();
        jdbc.sql("""
                INSERT INTO commission_parameter_selection(
                  id, detection_selection_id, parameter_id, sample_group_id, parameter_code_snapshot,
                  parameter_name_snapshot, unit_snapshot, required_snapshot, default_snapshot, sort_order, snapshot_json)
                SELECT md5('legacy-parameter-selection:' || t.commission_id::text || ':' || t.parameter_id::text || ':' || t.sample_id::text)::uuid,
                  md5('legacy-detection-selection:' || t.commission_id::text)::uuid, t.parameter_id,
                  md5('legacy-sample-group:' || t.sample_id::text)::uuid, p.code, p.name, p.unit,
                  p.required, p.default_commissioned, 1,
                  jsonb_build_object('source','development-fixture','parameterId',p.id,'code',p.code,
                    'name',p.name,'versionNumber',p.version_number,'valueType',p.value_type,
                    'definition',p.definition_json,'formula',p.formula,'unit',p.unit)
                FROM test_task t JOIN test_parameter p ON p.id=t.parameter_id
                WHERE t.commission_id='00000000-0000-0000-0000-000000000501'
                LIMIT 1
                ON CONFLICT (detection_selection_id, parameter_id, sample_group_id) DO NOTHING
                """).update();
        jdbc.sql("""
                UPDATE test_task t
                SET selection_id=md5('legacy-parameter-selection:' || t.commission_id::text || ':' || t.parameter_id::text || ':' || t.sample_id::text)::uuid
                WHERE t.commission_id='00000000-0000-0000-0000-000000000501' AND t.selection_id IS NULL
                """).update();
        jdbc.sql("""
                UPDATE commission SET selection_frozen_at=COALESCE(selection_frozen_at, updated_at)
                WHERE id='00000000-0000-0000-0000-000000000501'
                """).update();
    }

}

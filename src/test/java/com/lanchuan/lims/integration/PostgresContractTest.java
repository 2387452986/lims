package com.lanchuan.lims.integration;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.audit.AuditQueryService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.commission.CommissionService;
import com.lanchuan.lims.configuration.NumberingService;
import com.lanchuan.lims.configuration.BusinessNumberingConfigurationService;
import com.lanchuan.lims.configuration.NumberPatternRenderer;
import com.lanchuan.lims.configuration.AdministrativeDivisionService;
import com.lanchuan.lims.configuration.DataDictionaryService;
import com.lanchuan.lims.configuration.DocumentControlSnapshotService;
import com.lanchuan.lims.configuration.DocumentTemplateControlService;
import com.lanchuan.lims.configuration.FormulaEvaluator;
import com.lanchuan.lims.identity.AccountAdministrationService;
import com.lanchuan.lims.identity.AccessControlService;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import com.lanchuan.lims.identity.PasswordPolicy;
import com.lanchuan.lims.equipment.EquipmentOperationService;
import com.lanchuan.lims.masterdata.FoundationController;
import com.lanchuan.lims.masterdata.DepartmentService;
import com.lanchuan.lims.masterdata.OrganizationProfileService;
import com.lanchuan.lims.masterdata.PersonnelTitleTransferService;
import com.lanchuan.lims.masterdata.PersonnelSensitiveDataService;
import com.lanchuan.lims.masterdata.PersonnelService;
import com.lanchuan.lims.notification.NotificationProjectionService;
import com.lanchuan.lims.notification.NotificationRuleService;
import com.lanchuan.lims.nonconformance.ExceptionApprovalService;
import com.lanchuan.lims.nonconformance.NonconformanceService;
import com.lanchuan.lims.identity.RecentPasswordService;
import com.lanchuan.lims.report.ReportRevisionPolicy;
import com.lanchuan.lims.report.ReportQueueService;
import com.lanchuan.lims.report.ReportService;
import com.lanchuan.lims.sample.SampleLedgerService;
import com.lanchuan.lims.report.SignatureSchemeService;
import com.lanchuan.lims.report.SigningAssetService;
import com.lanchuan.lims.storedfile.StoredFileService;
import com.lanchuan.lims.task.TaskService;
import com.lanchuan.lims.task.TaskQueueService;
import com.lanchuan.lims.test.TestLedgerService;
import com.lanchuan.lims.test.DataChangeRequestService;
import com.lanchuan.lims.test.TestRecordService;
import com.lanchuan.lims.workbench.DomainLedgerExportService;
import com.lanchuan.lims.workbench.WorkbenchController;
import com.lanchuan.lims.workbench.WorkbenchSnapshotCache;
import com.lanchuan.lims.configuration.SystemExperienceService;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresContractTest {
    static PostgreSQLContainer<?> postgres;
    static DriverManagerDataSource dataSource;
    static String externalSchema;

    @BeforeAll
    static void migrate() {
        String externalUrl = System.getenv("TEST_DATABASE_URL");
        if (externalUrl == null || externalUrl.isBlank()) {
            postgres = new PostgreSQLContainer<>("postgres:18-alpine");
            postgres.start();
            dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        } else {
            String user = requiredEnvironment("TEST_DATABASE_USER");
            String password = requiredEnvironment("TEST_DATABASE_PASSWORD");
            externalSchema = requiredEnvironment("TEST_DATABASE_SCHEMA");
            if (!externalSchema.matches("showcase_[a-z0-9_]+")) {
                throw new IllegalArgumentException("TEST_DATABASE_SCHEMA must be an isolated showcase_* schema");
            }
            DriverManagerDataSource adminDataSource = new DriverManagerDataSource(externalUrl, user, password);
            new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA IF NOT EXISTS \"" + externalSchema + "\"");
            String separator = externalUrl.contains("?") ? "&" : "?";
            dataSource = new DriverManagerDataSource(externalUrl + separator + "currentSchema=" + externalSchema,
                    user, password);
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) postgres.stop();
        if (externalSchema != null && dataSource != null) {
            new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS \"" + externalSchema + "\" CASCADE");
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    @Test
    void commissionListIncludesIntakeStructureBeforeAPhysicalSampleExists() {
        JdbcClient client = JdbcClient.create(dataSource);
        UUID organization = client.sql("SELECT id FROM organization ORDER BY code LIMIT 1")
                .query(UUID.class).single();
        UUID department = client.sql("""
                SELECT id FROM department WHERE organization_id=:organization ORDER BY code LIMIT 1
                """).param("organization", organization).query(UUID.class).single();
        UUID actorId = UUID.randomUUID();
        String username = "commission-list-" + actorId.toString().substring(0, 8);
        client.sql("""
                INSERT INTO app_user(id,organization_id,department_id,username,password_hash,display_name,
                  enabled,must_change_password)
                VALUES (:id,:organization,:department,:username,'not-a-login-hash','委托列表契约账号',true,false)
                """).param("id", actorId).param("organization", organization).param("department", department)
                .param("username", username).update();
        AuthenticatedUser user = new AuthenticatedUser(actorId, null, organization, department,
                username, "委托列表契约账号",
                false, 0, "ALL", List.of("SYSTEM_ADMIN"), List.of("MODULE_READ"));
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(user);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID customer = UUID.randomUUID();
        client.sql("""
                INSERT INTO customer(id,organization_id,code,name,short_name,unit_type,commissioning_unit)
                VALUES (:id,:organization,:code,:name,:shortName,'ENTRUSTING',true)
                """).param("id", customer).param("organization", organization)
                .param("code", "LIST-" + suffix).param("name", "委托列表契约客户-" + suffix)
                .param("shortName", "列表契约").update();
        UUID project = UUID.randomUUID();
        client.sql("""
                INSERT INTO project(id,organization_id,customer_id,number,name,status,created_by)
                VALUES (:id,:organization,:customer,:number,:name,'ACTIVE',:actor)
                """).param("id", project).param("organization", organization).param("customer", customer)
                .param("number", "LIST-PROJECT-" + suffix).param("name", "委托列表契约工程")
                .param("actor", actorId).update();
        UUID revision = client.sql("""
                SELECT revision.id FROM detection_project_revision revision
                JOIN test_parameter parameter ON parameter.project_revision_id=revision.id
                  AND parameter.status='PUBLISHED' AND parameter.enabled
                WHERE revision.status='PUBLISHED'
                ORDER BY revision.project_node_id,revision.revision_number DESC LIMIT 1
                """).query(UUID.class).single();
        Map<String, Object> parameter = client.sql("""
                SELECT id,code,name,unit,required,default_commissioned
                FROM test_parameter WHERE project_revision_id=:revision AND status='PUBLISHED' AND enabled
                ORDER BY sort_order,id LIMIT 1
                """).param("revision", revision).query().singleRow();
        String number = "LIST-COMMISSION-" + suffix;
        UUID commission = UUID.randomUUID();
        client.sql("""
                INSERT INTO commission(id,organization_id,project_id,customer_id,number,contact_name,
                  commissioned_on,due_on,state,created_by,received_department_id)
                VALUES (:id,:organization,:project,:customer,:number,'列表契约联系人',current_date,
                  current_date+15,'DRAFT',:actor,:department)
                """).param("id", commission).param("organization", organization).param("project", project)
                .param("customer", customer).param("number", number).param("actor", actorId)
                .param("department", department).update();
        UUID selection = UUID.randomUUID();
        client.sql("""
                INSERT INTO commission_detection_selection(id,commission_id,project_revision_id,
                  project_name_snapshot,revision_number_snapshot)
                SELECT :id,:commission,:revision,node.name,revision.revision_number
                FROM detection_project_revision revision
                JOIN detection_catalog_node node ON node.id=revision.project_node_id
                WHERE revision.id=:revision
                """).param("id", selection).param("commission", commission).param("revision", revision).update();
        UUID sampleGroup = UUID.randomUUID();
        client.sql("""
                INSERT INTO commission_sample_group(id,commission_id,client_key,name,quantity,unit)
                VALUES (:id,:commission,:key,'待接收样品组',1,'组')
                """).param("id", sampleGroup).param("commission", commission)
                .param("key", "list-group-" + suffix).update();
        client.sql("""
                INSERT INTO commission_parameter_selection(id,detection_selection_id,parameter_id,sample_group_id,
                  parameter_code_snapshot,parameter_name_snapshot,unit_snapshot,required_snapshot,
                  default_snapshot,snapshot_json)
                VALUES (:id,:selection,:parameter,:sampleGroup,:code,:name,:unit,:required,:defaultValue,'{}'::jsonb)
                """).param("id", UUID.randomUUID()).param("selection", selection)
                .param("parameter", parameter.get("id")).param("sampleGroup", sampleGroup)
                .param("code", parameter.get("code")).param("name", parameter.get("name"))
                .param("unit", parameter.get("unit")).param("required", parameter.get("required"))
                .param("defaultValue", parameter.get("default_commissioned")).update();

        CommissionService service = new CommissionService(client, currentActor, mock(NumberingService.class),
                mock(AuditService.class), mock(DataScopeService.class), mock(DocumentControlSnapshotService.class));
        var page = service.list(new CommissionService.ListQuery(number, null, null, "", "", "", "", null,
                "", "", "", "", "", "", "", null, null, null, null, "COMMISSION", "", 20));

        assertThat(page.items()).extracting(CommissionService.Summary::number).containsExactly(number);
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void domainQueuesExecuteTheirScopedCursorQueries() {
        JdbcClient client = JdbcClient.create(dataSource);
        UUID organization = client.sql("SELECT id FROM organization ORDER BY code LIMIT 1")
                .query(UUID.class).single();
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), null, organization, null,
                "queue-contract", "领域队列契约", false, 0, "ALL", List.of("SYSTEM_ADMIN"),
                List.of("MODULE_READ", "EXPORT_DATA"));
        CurrentActor currentActor = mock(CurrentActor.class);

        var samples = new SampleLedgerService(client, currentActor).listFor(user,
                new SampleLedgerService.Filters("SAMPLE_LEDGER", "", "", null, null, "", 100));
        var tasks = new TaskQueueService(client, currentActor).listFor(user,
                new TaskQueueService.Filters("TASKS", "", "", null, null, "", 100));
        var tests = new TestLedgerService(client, currentActor).listFor(user,
                new TestLedgerService.Filters("TEST_LEDGER", "", "", "", null, null, "", 100));
        var reports = new ReportQueueService(client, currentActor).listFor(user,
                new ReportQueueService.Filters("REPORT_LEDGER", "", "", null, null, "", 100));

        assertThat(samples.items()).hasSizeLessThanOrEqualTo(100);
        assertThat(tasks.items()).hasSizeLessThanOrEqualTo(100);
        assertThat(tests.items()).hasSizeLessThanOrEqualTo(100);
        assertThat(reports.items()).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    void workbenchExecutesItsBoundedScopedProjectionAndReusesTheShortSnapshot() {
        JdbcClient client = JdbcClient.create(dataSource);
        UUID organization = client.sql("SELECT id FROM organization ORDER BY code LIMIT 1")
                .query(UUID.class).single();
        UUID department = client.sql("SELECT id FROM department WHERE organization_id=:organization ORDER BY code LIMIT 1")
                .param("organization", organization).query(UUID.class).single();
        UUID actorId = UUID.randomUUID();
        String username = "workbench-contract-" + actorId;
        client.sql("""
                INSERT INTO app_user(id,organization_id,department_id,username,password_hash,display_name,
                  enabled,must_change_password)
                VALUES (:id,:organization,:department,:username,'not-a-login-hash','工作台契约账号',true,false)
                """).param("id", actorId).param("organization", organization).param("department", department)
                .param("username", username).update();
        AuthenticatedUser user = new AuthenticatedUser(actorId, null, organization, department,
                username, "工作台契约账号", false, 0, "ALL", List.of("SYSTEM_ADMIN"),
                List.of("WORKBENCH_READ", "MODULE_READ", "MODULE_WRITE", "REPORT_REVIEW", "REPORT_ISSUE"));
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(user);
        DataScopeService dataScope = new DataScopeService(client, currentActor);
        var experience = new SystemExperienceService(client, currentActor, dataScope, mock(AuditService.class));
        var controller = new WorkbenchController(client, currentActor, experience, new WorkbenchSnapshotCache());

        var first = controller.get("ALL", LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1), "");
        var replay = controller.get("ALL", LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1), "");

        assertThat(first.pendingCommissions()).hasSizeLessThanOrEqualTo(8);
        assertThat(first.pendingReports()).hasSizeLessThanOrEqualTo(8);
        assertThat(first.quickActions()).isNotEmpty();
        assertThat(replay).isSameAs(first);
    }

    @Test
    void controlledQualityAndSafetyListsExecuteScopedCursorQueries() {
        JdbcClient client = JdbcClient.create(dataSource);
        Map<String, Object> identity = client.sql("""
                SELECT id,person_id,organization_id,department_id,username,display_name
                FROM app_user ORDER BY username LIMIT 1
                """).query().singleRow();
        AuthenticatedUser user = new AuthenticatedUser((UUID) identity.get("id"),
                (UUID) identity.get("person_id"), (UUID) identity.get("organization_id"),
                (UUID) identity.get("department_id"), String.valueOf(identity.get("username")),
                String.valueOf(identity.get("display_name")), false, 0, "ALL", List.of("SYSTEM_ADMIN"),
                List.of("MODULE_READ", "SYSTEM_ADMIN"));
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(user);
        DataScopeService dataScope = new DataScopeService(client, currentActor);

        var exceptions = new ExceptionApprovalService(client, currentActor, mock(AuditService.class), dataScope)
                .list("", 100);
        var changes = new DataChangeRequestService(client, currentActor, mock(AuditService.class),
                new ObjectMapper(), mock(TestRecordService.class), dataScope).list("", 100);
        var briefings = new SystemExperienceService(client, currentActor, dataScope, mock(AuditService.class))
                .briefings("", "", "", 100);

        assertThat(exceptions.items()).hasSizeLessThanOrEqualTo(100);
        assertThat(changes.items()).hasSizeLessThanOrEqualTo(100);
        assertThat(briefings.items()).hasSizeLessThanOrEqualTo(100);
        assertThat(client.sql("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema()
                  AND indexname IN ('exception_approval_list_ix','data_change_request_list_ix')
                """).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void domainExportQueueReplaysOnlyTheSameIdempotentRequest() {
        JdbcClient client = JdbcClient.create(dataSource);
        Map<String, Object> identity = client.sql("""
                SELECT id,person_id,organization_id,department_id,username,display_name
                FROM app_user ORDER BY username LIMIT 1
                """).query().singleRow();
        AuthenticatedUser user = new AuthenticatedUser((UUID) identity.get("id"),
                (UUID) identity.get("person_id"), (UUID) identity.get("organization_id"),
                (UUID) identity.get("department_id"), String.valueOf(identity.get("username")),
                String.valueOf(identity.get("display_name")), false, 0, "ALL", List.of("SYSTEM_ADMIN"),
                List.of("MODULE_READ", "EXPORT_DATA"));
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(user);
        DomainLedgerExportService service = new DomainLedgerExportService(client, currentActor,
                mock(AuditService.class), new ObjectMapper(), new SampleLedgerService(client, currentActor),
                new TaskQueueService(client, currentActor), new TestLedgerService(client, currentActor),
                new ReportQueueService(client, currentActor));
        String key = "showcase-domain-export-" + UUID.randomUUID();
        var request = new SampleLedgerService.Filters("SAMPLE_LEDGER", "", "", null, null, "", 50);

        var first = service.enqueueSample(request, key);
        var replay = service.enqueueSample(request, key);

        assertThat(replay.jobId()).isEqualTo(first.jobId());
        assertThatThrownBy(() -> service.enqueueSample(new SampleLedgerService.Filters(
                "SAMPLE_LEDGER", "different", "", null, null, "", 50), key))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void flywayCreatesAllForwardMigrationsAndAuditRowsAreImmutable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(56);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'sample_updated_cursor_v52_ix','sample_retained_cursor_v52_ix','sample_age_schedule_cursor_v52_ix',
                  'test_task_updated_cursor_v52_ix','test_task_due_cursor_v52_ix','test_task_returned_cursor_v52_ix',
                  'test_record_updated_cursor_v52_ix','test_record_state_result_cursor_v52_ix',
                  'report_updated_cursor_v52_ix','report_state_updated_cursor_v52_ix',
                  'report_version_report_revision_v52_ix')
                """, Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_business_category", Integer.class)).isEqualTo(83);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'business_numbering_rule','business_number_sequence','template_numbering_rule',
                  'audit_business_category') ORDER BY table_name
                """)).hasSize(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'business_numbering_rule_one_draft_ix','business_numbering_rule_one_published_series_ix',
                  'business_numbering_rule_published_resource_ix','business_numbering_rule_draft_code_ix',
                  'business_numbering_rule_published_code_ix','business_numbering_rule_list_ix',
                  'template_numbering_rule_one_draft_ix','template_numbering_rule_one_published_series_ix',
                  'template_numbering_rule_published_type_ix','template_numbering_rule_draft_code_ix',
                  'template_numbering_rule_published_code_ix','template_numbering_rule_list_ix',
                  'audit_event_org_time_ix','audit_event_org_resource_time_ix','audit_event_org_operation_time_ix')
                """, Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForList("""
                SELECT audit_operation_type(action) operation FROM (VALUES
                  ('LOGIN_SUCCESS'),('REPORT_VIEWED'),('PROJECT_CREATED'),('REPORT_UPDATED'),
                  ('REPORT_DELETED'),('LOGOUT')) action(action) ORDER BY action
                """, String.class)).containsExactlyInAnyOrder("LOGIN", "BROWSE", "CREATE", "UPDATE", "DELETE", "LOGOUT");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='organization' AND column_name IN (
                  'short_name','organization_nature','unified_credit_code','registered_on',
                  'registration_authority','postal_code','province','city','district','detailed_address',
                  'registered_capital','telephone','fax','email','legal_representative_id',
                  'frequent_contact_name','frequent_contact_phone','primary_responsible_person_id',
                  'quality_responsible_person_id','technical_responsible_person_id','declared_equipment_count',
                  'declared_equipment_original_value','testing_area','workplace_area','profile_remark',
                  'business_license_file_id')
                """, Integer.class)).isEqualTo(26);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM permission_catalog_node WHERE origin='SOURCE'
                """, Integer.class)).isEqualTo(524);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM permission_client
                WHERE client_code IN ('PC','MINI_PROGRAM','CUSTOMER_PC','FIELD_APP')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reference_configuration_import_manifest
                WHERE resource_type='PERMISSION_CATALOG'
                  AND capture_sha256='38db336a2be5756a69ed5ce491f9dcf63b4499b5769c19da00041374e2b8720a'
                  AND record_count=524
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app_role role
                WHERE role.current_policy_revision_id IS NOT NULL
                  AND EXISTS(SELECT 1 FROM role_policy_revision revision
                    WHERE revision.id=role.current_policy_revision_id AND revision.role_id=role.id)
                """, Integer.class)).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM app_role", Integer.class));
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'organization_office_address','organization_office_address_revision')
                ORDER BY table_name
                """)).hasSize(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'organization_unified_credit_code_ci_ux','organization_business_license_file_ux',
                  'organization_one_correspondence_address_ux','organization_office_address_list_ix',
                  'organization_office_address_history_ix')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='department' AND column_name IN (
                  'sort_order','responsible_person_id','supervising_person_id','retired_at','retired_reason')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name='department_detection_project'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'department_tree_ix','department_active_sibling_name_ci_ux',
                  'department_responsible_person_ix','department_supervising_person_ix',
                  'department_detection_project_project_ix')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger trigger
                JOIN pg_class relation ON relation.oid=trigger.tgrelid
                JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
                WHERE namespace.nspname=current_schema() AND NOT trigger.tgisinternal
                  AND trigger.tgname IN ('organization_office_address_revision_immutable_trg',
                    'organization_office_address_current_revision_trg')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='person' AND column_name IN (
                  'gender','national_id_cipher','national_id_nonce','national_id_digest','national_id_last4',
                  'birth_date','position','person_type','office_location','specialty','education',
                  'testing_started_on','labor_relationship','testing_qualification','title_issuer','remark',
                  'application_state','identity_state','electronic_signature_state','avatar_file_id',
                  'id_front_file_id','id_back_file_id','social_security_file_id')
                """, Integer.class)).isEqualTo(23);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name='person_detection_capability'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'data_dictionary_series','data_dictionary_revision','data_dictionary_value')
                ORDER BY table_name
                """)).hasSize(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'data_dictionary_code_ci_ux','data_dictionary_source_ux','data_dictionary_tree_ix',
                  'data_dictionary_one_draft_ix','data_dictionary_one_published_ix',
                  'data_dictionary_revision_state_ix','data_dictionary_value_source_ux',
                  'data_dictionary_value_order_ix')
                """, Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger trigger
                JOIN pg_class relation ON relation.oid=trigger.tgrelid
                JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
                WHERE namespace.nspname=current_schema() AND NOT trigger.tgisinternal
                  AND trigger.tgname IN ('data_dictionary_series_hierarchy_trg',
                    'data_dictionary_revision_guard_trg','data_dictionary_value_guard_trg')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM data_dictionary_series WHERE source_system='showcase_catalog'
                """, Integer.class)).isEqualTo(92);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM data_dictionary_value value
                JOIN data_dictionary_revision revision ON revision.id=value.revision_id
                JOIN data_dictionary_series series ON series.id=revision.series_id
                WHERE series.source_system='showcase_catalog'
                """, Integer.class)).isEqualTo(428);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reference_configuration_import_manifest
                WHERE resource_type='DATA_DICTIONARY' AND source_system='showcase_catalog' AND record_count=520
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM document_template_control WHERE source_system='showcase_catalog'
                """, Integer.class)).isEqualTo(1452);
        for (String documentType : List.of("REPORT", "COMMISSION", "TASK")) {
            assertThat(jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE link_status='LINKED') linked,
                  count(*) FILTER (WHERE link_status='AMBIGUOUS') ambiguous,
                  count(*) FILTER (WHERE link_status='SOURCE_ONLY') source_only,
                  count(*) FILTER (WHERE controlled_number IS NOT NULL
                    OR template_version_number IS NOT NULL) configured
                FROM document_template_control
                WHERE source_system='showcase_catalog' AND document_type=?
                """, documentType)).containsEntry("linked", 330L).containsEntry("ambiguous", 131L)
                    .containsEntry("source_only", 23L).containsEntry("configured", 0L);
        }
        assertThat(jdbc.queryForMap("""
                SELECT count(DISTINCT source_occurrence) occurrences,
                  count(DISTINCT project_name_snapshot || E'\\n' || parent_name_snapshot) pairs
                FROM document_template_control WHERE source_system='showcase_catalog'
                """)).containsEntry("occurrences", 484L).containsEntry("pairs", 417L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'document_template_control_project_ux','document_template_control_page_ix',
                  'document_template_control_search_ix','document_template_control_link_status_ix',
                  'report_version_document_control_project_ix','report_version_document_control_control_ix',
                  'commission_document_control_project_ix','commission_document_control_control_ix',
                  'task_document_control_project_ix','task_document_control_control_ix')
                """, Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger trigger
                JOIN pg_class relation ON relation.oid=trigger.tgrelid
                JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
                WHERE namespace.nspname=current_schema() AND NOT trigger.tgisinternal
                  AND trigger.tgname IN ('document_template_control_source_guard_trg',
                    'report_document_control_snapshot_immutable_trg',
                    'commission_document_control_snapshot_immutable_trg',
                    'task_document_control_snapshot_immutable_trg')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reference_configuration_import_manifest
                WHERE resource_type='REPORT_TEMPLATE_CONTROL' AND source_system='showcase_catalog'
                  AND capture_sha256='555930fd7cf880332da5e765038e556b6afc4cfc33ebbc5ea73fce64928af684'
                  AND record_count=484
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reference_configuration_import_manifest
                WHERE resource_type IN ('COMMISSION_TEMPLATE_CONTROL','TASK_TEMPLATE_CONTROL')
                  AND source_system='showcase_catalog'
                  AND capture_sha256='472e86d0460898967d5ba774d870b0ff7d98f304b2bc63a7bec35109195c0fc1'
                  AND record_count=484
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema=current_schema() AND table_name='organization_qualification_record'
                  AND constraint_name IN ('organization_qualification_number_nonblank_check',
                    'organization_qualification_authority_nonblank_check',
                    'organization_qualification_scope_nonblank_check',
                    'organization_qualification_details_controlled_check')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'organization_qualification_certificate_ci_ux','organization_qualification_issued_on_ix',
                  'organization_qualification_details_gin_ix','organization_qualification_original_file_ix',
                  'organization_qualification_copy_file_ix','metrology_qualification_certificate_file_ix',
                  'metrology_qualification_other_file_ix')
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                WHERE trigger_schema=current_schema()
                  AND trigger_name='organization_qualification_controlled_content_trg'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema=current_schema() AND table_name='metrology_qualification_record'
                  AND constraint_name IN ('metrology_qualification_number_nonblank_check',
                    'metrology_qualification_authority_nonblank_check')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'metrology_qualification_certificate_ci_ux','metrology_qualification_issued_on_ix')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                WHERE trigger_schema=current_schema()
                  AND trigger_name='metrology_qualification_controlled_content_trg'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='sample_shelf' AND column_name='parent_id'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'sample_shelf_number_ci_ux','sample_shelf_root_cursor_ix','sample_shelf_tree_order_ix')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname=current_schema() AND indexname='sample_shelf_root_cursor_ix'
                """, String.class)).contains("sort_order", "upper").doesNotContain("updated_at");
        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname=current_schema() AND indexname='sample_shelf_tree_order_ix'
                """, String.class)).contains("parent_id", "sort_order", "upper", "INCLUDE (active)");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger trigger
                JOIN pg_class relation ON relation.oid=trigger.tgrelid
                JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
                WHERE namespace.nspname=current_schema() AND NOT trigger.tgisinternal
                  AND trigger.tgname='sample_shelf_hierarchy_guard_trg'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema()
                  AND indexname IN ('person_national_id_digest_ux','person_filter_ix',
                    'person_department_cursor_ix','app_user_person_ux','person_detection_capability_project_ix')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM permission WHERE code IN (
                  'PERSON_ADMIN','PERSON_SENSITIVE_READ','PERSON_IMPORT','PERSON_EXPORT')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema()
                  AND indexname='stored_file_content_ix'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema=current_schema() AND table_name='stored_file'
                  AND constraint_type='UNIQUE'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema=current_schema() AND table_name='training_record'
                  AND constraint_name IN ('training_record_method_check','training_record_assessment_check')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema()
                  AND indexname IN ('training_record_method_date_ix','training_record_certificate_file_ux')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                WHERE trigger_schema=current_schema() AND trigger_name='training_record_void_content_trg'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema=current_schema() AND table_name='project_team_member'
                  AND constraint_name IN ('project_team_member_duty_check','project_team_member_sort_order_check')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema()
                  AND indexname IN ('project_team_member_person_ix','project_team_state_name_ix')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger trigger
                JOIN pg_class relation ON relation.oid=trigger.tgrelid
                JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
                WHERE namespace.nspname=current_schema() AND NOT trigger.tgisinternal
                  AND trigger.tgname IN ('project_team_membership_team_trg','project_team_membership_member_trg')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema=current_schema() AND table_name='person_title_record'
                  AND constraint_name IN ('person_title_certificate_number_check','person_title_specialty_name_check',
                    'person_title_issuing_authority_check')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema()
                  AND indexname IN ('person_title_certificate_ci_ux','person_title_attachment_file_ux',
                    'person_title_person_cursor_ix')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                WHERE trigger_schema=current_schema() AND trigger_name='person_title_controlled_content_trg'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='person_qualification'
                  AND column_name IN ('issuing_authority','practice_number','practice_organization','specialties',
                    'remark','certificate_file_id','submitted_at','submitted_by','reviewed_at','reviewed_by',
                    'created_by','updated_by')
                """, Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'person_qualification_cursor_ix','person_qualification_status_validity_ix',
                  'person_qualification_issuer_ix','person_qualification_specialties_gin_ix',
                  'person_qualification_file_ix','person_qualification_certificate_file_ux')
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname='pg_stat_statements'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema=current_schema() AND table_name='archive_package'
                  AND constraint_name='archive_package_report_version_key'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'training_record','project_team','project_team_member','person_title_record',
                  'organization_qualification_record','metrology_qualification_record','sample_shelf')
                ORDER BY table_name
                """)).hasSize(7);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN ('signature_scheme','signature_scheme_position')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'numbering_flow_rule','numbering_flow_rule_pattern',
                  'numbering_flow_rule_project','numbering_flow_template','reference_configuration_import_manifest')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name='detection_standard_parameter_scope'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname=current_schema() AND indexname='test_parameter_standard_standard_ix'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='report_version' AND column_name='signature_scheme_id'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'equipment_category','equipment_detection_project','equipment_inspection',
                  'equipment_borrow','equipment_borrow_companion','equipment_usage_record','equipment_movement')
                ORDER BY table_name
                """)).hasSize(7);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'equipment_one_open_inspection_ux','equipment_one_open_borrow_ux','equipment_one_open_usage_ux')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'customer_settlement_contact','customer_person','customer_account_link','project_party',
                  'project_detection_plan','project_progress_mark','project_structure_part','commission_change_request',
                  'commission_revision')
                ORDER BY table_name
                """)).hasSize(9);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='project'
                  AND column_name IN ('regulatory_organization','pile_serial_number','project_team_id',
                    'progress_percent','latitude','longitude')
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(sum(jsonb_array_length(content_json->'values')),0)
                FROM configuration_version
                WHERE configuration_type='DICTIONARY' AND state='PUBLISHED'
                  AND code IN ('PROJECT_TYPE','PROJECT_GRADE','DEFAULT_DETECTION_CATEGORY')
                """, Integer.class)).isEqualTo(23);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reference_configuration_import_manifest
                WHERE resource_type='PROJECT_FORM_DICTIONARY' AND source_system='showcase_catalog'
                  AND record_count=23
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'detection_sample_field_definition','detection_sample_field_standard',
                  'detection_age_report_option','commission_age_report_selection',
                  'commission_sample_field_value','commission_attachment','commission_relation','commission_transfer')
                ORDER BY table_name
                """)).hasSize(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='commission'
                  AND column_name='internal_sequence'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname=current_schema() AND indexname IN (
                  'commission_internal_sequence_v24_ix','sample_group_commission_v24_ix','sample_group_sample_v24_ix')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'commission_sample_group_part','commission_test_termination',
                  'commission_electronic_document','report_print_authorization')
                ORDER BY table_name
                """)).hasSize(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='commission' AND column_name IN (
                  'external_number_bound_at','external_number_bound_by','report_print_restricted',
                  'report_print_limit','report_print_restriction_reason')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='background_job' AND column_name IN (
                  'result_file_id','created_by','resource_type','resource_id','version')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'commission_external_number_v26_ux','commission_electronic_document_latest_v26_ix',
                  'report_print_authorization_limit_v26_ix','background_job_creator_v26_ix')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'contract_price_template','contract_price_template_item','business_contract',
                  'contract_financial_entry','fee_statement','finance_receipt','finance_payment')
                ORDER BY table_name
                """)).hasSize(7);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM permission
                WHERE code IN ('CONTRACT_MANAGE','FINANCE_MANAGE','FINANCE_APPROVE')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'desktop_theme','safety_briefing_rule','task_safety_briefing_read','transition_time_setting')
                ORDER BY table_name
                """)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM desktop_theme WHERE state='ACTIVE'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transition_time_setting", Integer.class))
                .isEqualTo(8);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN ('warning_record','warning_record_attachment')
                ORDER BY table_name
                """)).hasSize(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='sample' AND column_name IN (
                  'sampling_date','molding_date','planned_test_date','age_days','curing_method',
                  'strength_grade','structure_part','chip_code','represented_quantity','represented_unit')
                """, Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='test_task'
                  AND column_name IN ('accepted_at','started_at','completed_at')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'sample_planned_test_v29_ix','test_task_field_ledger_v29_ix','warning_record_list_v29_ix')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permission WHERE code='WARNING_RESOLVE'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_name IN (
                  'quality_summary_document','quality_summary_content','quality_summary_attachment',
                  'concrete_strength_assessment','concrete_strength_result_snapshot')
                ORDER BY table_name
                """)).hasSize(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN (
                  'quality_summary_org_list_ix','quality_summary_project_ix','quality_summary_customer_ix',
                  'quality_summary_content_lookup_ix','concrete_strength_org_list_ix',
                  'concrete_strength_project_period_ix','concrete_strength_parameter_ix',
                  'concrete_strength_snapshot_record_ix')
                """, Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint constraint_row
                JOIN pg_class table_row ON table_row.oid=constraint_row.conrelid
                JOIN pg_namespace namespace_row ON namespace_row.oid=table_row.relnamespace
                WHERE namespace_row.nspname=current_schema() AND table_row.relname='concrete_strength_assessment'
                  AND constraint_row.contype='c' AND pg_get_constraintdef(constraint_row.oid) LIKE '%REVIEWED%'
                """, Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM detection_sample_field_definition
                WHERE project_revision_id='636b2dcc-dde3-507d-b06d-923a69b9777a'
                  AND source_system='showcase_catalog'
                """, Integer.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM detection_sample_field_definition
                WHERE project_revision_id='636b2dcc-dde3-507d-b06d-923a69b9777a'
                  AND required
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname=current_schema() AND indexname='configuration_one_published_ux'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(content_json->'items') FROM configuration_version
                WHERE configuration_type='DICTIONARY' AND code='ADMINISTRATIVE_DIVISION' AND state='PUBLISHED'
                """, Integer.class)).isEqualTo(34);
        assertThat(jdbc.queryForObject("""
                SELECT sum(jsonb_array_length(province->'children'))
                FROM configuration_version configuration
                CROSS JOIN LATERAL jsonb_array_elements(configuration.content_json->'items') province
                WHERE configuration.configuration_type='DICTIONARY'
                  AND configuration.code='ADMINISTRATIVE_DIVISION' AND configuration.state='PUBLISHED'
                """, Integer.class)).isEqualTo(364);
        assertThat(jdbc.queryForObject("""
                SELECT sum(jsonb_array_length(city->'children'))
                FROM configuration_version configuration
                CROSS JOIN LATERAL jsonb_array_elements(configuration.content_json->'items') province
                CROSS JOIN LATERAL jsonb_array_elements(province->'children') city
                WHERE configuration.configuration_type='DICTIONARY'
                  AND configuration.code='ADMINISTRATIVE_DIVISION' AND configuration.state='PUBLISHED'
                """, Integer.class)).isEqualTo(3025);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reference_configuration_import_manifest
                WHERE resource_type='ADMINISTRATIVE_DIVISION' AND source_system='showcase_catalog'
                  AND capture_sha256='527433a0b802cf70b32c9c45f0b7e8c355d82568dc5a11b0d8bc243299e39a8a'
                  AND record_count=3423
                """, Integer.class)).isEqualTo(1);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO audit_event(id, request_id, action, resource_type) VALUES (?,?,?,?)",
                id, "test", "CREATED", "TEST");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET action='MUTATED' WHERE id=?", id))
                .hasMessageContaining("audit events are immutable");
    }

    @Test
    void organizationAddressRevisionsAreConsistentUniqueAndImmutable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject(
                "SELECT id FROM department WHERE organization_id=? ORDER BY code LIMIT 1", UUID.class, organization);
        UUID person = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                person, department, "ORG-ADDRESS-" + person.toString().substring(0, 8), "单位地址约束测试人员");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?,?)
                """, actor, person, organization, department, "org-address-" + actor,
                "not-a-login-hash", "单位地址约束测试账号");
        UUID address = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO organization_office_address(id,organization_id,current_revision_id,
                      correspondence_address,created_by) VALUES (?,?,?,?,?)
                    """, address, organization, revision, true, actor);
            jdbc.update("""
                    INSERT INTO organization_office_address_revision(id,address_id,revision_number,office_name,
                      province,city,district,detailed_address,postal_code,changed_on,
                      correspondence_address,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, revision, address, 1, "SHOWCASE 主办公场所", "海南省", "海口市", "美兰区",
                    "SHOWCASE 测试地址", "570000", LocalDate.now(), true, actor);
        });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM organization_office_address address
                JOIN organization_office_address_revision revision ON revision.id=address.current_revision_id
                WHERE address.id=? AND revision.address_id=address.id
                  AND revision.correspondence_address=address.correspondence_address
                """, Integer.class, address)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO organization_office_address(id,organization_id,current_revision_id,
                  correspondence_address,created_by) VALUES (?,?,?,?,?)
                """, UUID.randomUUID(), organization, UUID.randomUUID(), true, actor))
                .hasMessageContaining("organization_one_correspondence_address_ux");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE organization_office_address_revision SET office_name='覆盖历史' WHERE id=?", revision))
                .hasMessageContaining("organization office address revisions are immutable");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM organization_office_address_revision WHERE id=?", revision))
                .hasMessageContaining("organization office address revisions are immutable");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE organization SET postal_code='INVALID' WHERE id=?", organization))
                .hasMessageContaining("organization_postal_code_check");
    }

    @Test
    void organizationProfileServiceControlsAddressLifecycleAndEsignReadiness() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject(
                "SELECT id FROM department WHERE organization_id=? ORDER BY code LIMIT 1", UUID.class, organization);
        UUID person = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                person, department, "ORG-SERVICE-" + person.toString().substring(0, 8), "单位资料服务测试人员");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?,?)
                """, actorId, person, organization, department, "org-service-" + actorId,
                "not-a-login-hash", "单位资料服务测试账号");

        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, person, organization, department,
                "org-service-test", "单位资料服务测试", false, 0, "ALL", List.of("SYSTEM_ADMIN"),
                List.of("MODULE_READ", "MODULE_WRITE", "SYSTEM_ADMIN", "PERSON_SENSITIVE_READ")));
        AuditService audit = mock(AuditService.class);
        OrganizationProfileService service = new OrganizationProfileService(JdbcClient.create(dataSource),
                currentActor, audit, new AdministrativeDivisionService(JdbcClient.create(dataSource), new ObjectMapper()),
                mock(StoredFileService.class));
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        OrganizationProfileService.OfficeAddressView created = transaction.execute(status ->
                service.createOfficeAddress(new OrganizationProfileService.OfficeAddressWrite(
                        "SHOWCASE 服务测试场所", "海南省", "海口市", "美兰区", "SHOWCASE 初始地址",
                        "570000", LocalDate.now(), false, null)));
        assertThat(created).isNotNull();
        assertThat(created.revisionNumber()).isEqualTo(1);
        assertThat(created.allowedActions()).containsExactly("REVISE", "RETIRE");

        assertThatThrownBy(() -> transaction.execute(status -> service.updateOfficeAddress(created.id(),
                new OrganizationProfileService.OfficeAddressWrite("SHOWCASE 服务测试场所", "海南省", "海口市",
                        "美兰区", "SHOWCASE 陈旧版本地址", "570000", LocalDate.now(), false,
                        created.version() + 1))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("VERSION_CONFLICT"));

        OrganizationProfileService.OfficeAddressView revised = transaction.execute(status ->
                service.updateOfficeAddress(created.id(), new OrganizationProfileService.OfficeAddressWrite(
                        "SHOWCASE 服务测试场所", "海南省", "海口市", "美兰区", "SHOWCASE 修订地址",
                        "570000", LocalDate.now(), false, created.version())));
        assertThat(revised).isNotNull();
        assertThat(revised.revisionNumber()).isEqualTo(2);
        assertThat(service.officeAddressHistory(created.id())).extracting(
                OrganizationProfileService.OfficeAddressRevision::revisionNumber).containsExactly(2, 1);

        var retired = transaction.execute(status -> service.transitionOfficeAddress(created.id(),
                new TransitionCommand("RETIRE", revised.version(), "SITE_RELOCATED", "SHOWCASE 停用验证")));
        assertThat(retired).isNotNull();
        assertThat(retired.state()).isEqualTo("RETIRED");
        var restored = transaction.execute(status -> service.transitionOfficeAddress(created.id(),
                new TransitionCommand("RESTORE", retired.version(), "SITE_REOPENED", "SHOWCASE 恢复验证")));
        assertThat(restored).isNotNull();
        assertThat(restored.state()).isEqualTo("ACTIVE");

        OrganizationProfileService.Profile profile = service.profile();
        assertThatThrownBy(() -> service.requestElectronicSignatureEnrollment(
                new OrganizationProfileService.ElectronicSignatureEnrollment(true, true, true, profile.version())))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    ApiException api = (ApiException) exception;
                    assertThat(api.code()).isEqualTo("ORGANIZATION_PROFILE_INCOMPLETE");
                    assertThat(api.blockers()).isNotEmpty();
                });
        verify(audit).recordRejected(eq("ORGANIZATION_ESIGN_ENROLLMENT_BLOCKED"), eq("ORGANIZATION"),
                eq(organization), any(), eq("ORGANIZATION_PROFILE_INCOMPLETE"), any());
    }

    @Test
    void identicalFileContentKeepsIndependentAuthorizationMetadata() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID actor = jdbc.queryForObject("SELECT id FROM app_user ORDER BY created_at,id LIMIT 1", UUID.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String digest = "a".repeat(64);
        jdbc.update("""
                INSERT INTO stored_file(id,sha256,size_bytes,media_type,original_name,storage_path,purpose,uploaded_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, first, digest, 128L, "application/pdf", "one.pdf", "aa/aa/" + digest,
                "PERSON_EXPORT", actor);
        jdbc.update("""
                INSERT INTO stored_file(id,sha256,size_bytes,media_type,original_name,storage_path,purpose,uploaded_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, second, digest, 128L, "application/pdf", "two.pdf", "aa/aa/" + digest,
                "PERSON_IMPORT_TEMPLATE", actor);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_file WHERE sha256=? AND size_bytes=?",
                Integer.class, digest, 128L)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT purpose) FROM stored_file WHERE id IN (?,?)",
                Integer.class, first, second)).isEqualTo(2);
    }

    @Test
    void personnelQualificationContentIsControlledAndCertificateMetadataCannotBeReused() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID department = jdbc.queryForObject("SELECT id FROM department ORDER BY created_at,id LIMIT 1", UUID.class);
        UUID actor = jdbc.queryForObject("SELECT id FROM app_user ORDER BY created_at,id LIMIT 1", UUID.class);
        UUID person = UUID.randomUUID();
        UUID file = UUID.randomUUID();
        UUID qualification = UUID.randomUUID();
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                person, department, "QUAL-" + person.toString().substring(0, 8), "资格约束测试人员");
        String digest = "b".repeat(64);
        jdbc.update("""
                INSERT INTO stored_file(id,sha256,size_bytes,media_type,original_name,storage_path,purpose,uploaded_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, file, digest, 128L, "image/png", "qualification.png", "bb/bb/" + digest,
                "PERSON_QUALIFICATION_CERTIFICATE", actor);
        jdbc.update("""
                INSERT INTO person_qualification(id,person_id,qualification_code,certificate_number,valid_from,
                  valid_until,status,issuing_authority,practice_number,practice_organization,specialties,
                  certificate_file_id,created_by,updated_by)
                VALUES (?,?,?,?,current_date-1,current_date+365,'DRAFT',?,?,?,
                  ARRAY['混凝土工程','工程师']::varchar[],?,?,?)
                """, qualification, person, "QUALIFICATION-CONTRACT", "CERTIFICATE-CONTRACT",
                "约束测试发证单位", "PRACTICE-CONTRACT", "约束测试执业单位",
                file, actor, actor);
        assertThat(jdbc.update("UPDATE person_qualification SET remark='草稿可修改' WHERE id=?", qualification))
                .isEqualTo(1);
        assertThat(jdbc.update("UPDATE person_qualification SET status='SUBMITTED',version=version+1 WHERE id=?", qualification))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE person_qualification SET issuing_authority='越过版本控制' WHERE id=?", qualification))
                .hasMessageContaining("submitted personnel qualification content is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO person_qualification(id,person_id,qualification_code,certificate_number,valid_from,
                  valid_until,status,certificate_file_id)
                VALUES (?,?,?,?,current_date-1,current_date+365,'DRAFT',?)
                """, UUID.randomUUID(), person, "QUALIFICATION-CONTRACT-2", "CERTIFICATE-CONTRACT-2", file))
                .hasMessageContaining("person_qualification_certificate_file_ux");
    }

    @Test
    void personnelMigrationPreservesAndSeparatesLegacyAccountsSharingOneProfile() {
        String schema = "showcase_personnel_upgrade";
        String url = System.getenv("TEST_DATABASE_URL");
        String user;
        String password;
        if (url == null || url.isBlank()) {
            url = postgres.getJdbcUrl(); user = postgres.getUsername(); password = postgres.getPassword();
        } else {
            user = requiredEnvironment("TEST_DATABASE_USER"); password = requiredEnvironment("TEST_DATABASE_PASSWORD");
        }
        DriverManagerDataSource admin = new DriverManagerDataSource(url, user, password);
        JdbcTemplate root = new JdbcTemplate(admin);
        root.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        root.execute("CREATE SCHEMA " + schema);
        String separator = url.contains("?") ? "&" : "?";
        DriverManagerDataSource upgrade = new DriverManagerDataSource(url + separator + "currentSchema=" + schema,
                user, password);
        try {
            Flyway.configure().dataSource(upgrade).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("30")).load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(upgrade);
            jdbc.update("""
                    INSERT INTO person(id,department_id,employee_number,name)
                    VALUES ('10000000-0000-0000-0000-000000000001',
                      '00000000-0000-0000-0000-000000000011','LEGACY-DUPLICATE','既有共用档案')
                    """);
            jdbc.update("""
                    INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name)
                    VALUES
                    ('10000000-0000-0000-0000-000000000011','10000000-0000-0000-0000-000000000001',
                      '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000011',
                      'legacy-one','not-a-real-password','既有账号一'),
                    ('10000000-0000-0000-0000-000000000012','10000000-0000-0000-0000-000000000001',
                      '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000011',
                      'legacy-two','not-a-real-password','既有账号二')
                    """);
            Flyway.configure().dataSource(upgrade).locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT person_id) FROM app_user WHERE username LIKE 'legacy-%'", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username LIKE 'legacy-%'", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='PERSON_ACCOUNT_PROFILE_REBOUND'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname='app_user_person_ux'", Integer.class))
                    .isEqualTo(1);
        } finally {
            root.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void systemExperienceControlsEnforceSingleActiveVersionsAndReadTimeBounds() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID projectRevision = jdbc.queryForObject(
                "SELECT id FROM detection_project_revision WHERE status='PUBLISHED' ORDER BY id LIMIT 1", UUID.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO desktop_theme(id,organization_id,code,name,brand_900,brand_600,brand_100,
                  surface_background,density,radius_px,state,activated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE',now())
                """, UUID.randomUUID(), organization, "SECOND-ACTIVE", "第二个活动风格", "#07377F", "#1769E0",
                "#EAF2FF", "#F4F7FB", "COMPACT", 10)).hasMessageContaining("desktop_theme_one_active_ux");

        jdbc.update("""
                INSERT INTO safety_briefing_rule(id,organization_id,detection_project_revision_id,code,title,
                  briefing_type,content,read_seconds,state,activated_at)
                VALUES (?,?,?,?,?,'PROJECT_SAFETY',?,5,'ACTIVE',now())
                """, UUID.randomUUID(), organization, projectRevision, "CONSTRAINT-BRIEFING-1",
                "约束测试交底", "SHOWCASE 受控测试内容");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO safety_briefing_rule(id,organization_id,detection_project_revision_id,code,title,
                  briefing_type,content,read_seconds,state,activated_at)
                VALUES (?,?,?,?,?,'PROJECT_SAFETY',?,5,'ACTIVE',now())
                """, UUID.randomUUID(), organization, projectRevision, "CONSTRAINT-BRIEFING-2",
                "第二个活动交底", "SHOWCASE 受控测试内容"))
                .hasMessageContaining("safety_briefing_one_active_ux");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO safety_briefing_rule(id,organization_id,detection_project_revision_id,code,title,
                  briefing_type,content,read_seconds)
                VALUES (?,?,?,?,?,'OTHER',?,4)
                """, UUID.randomUUID(), organization, projectRevision, "CONSTRAINT-BRIEFING-SECONDS",
                "非法阅读时长", "SHOWCASE 受控测试内容"))
                .hasMessageContaining("violates check constraint");
    }

    @Test
    void controlledCommissionTemplateLookupIgnoresLegacyPlaceholderContent() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID placeholder = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO configuration_version(id,configuration_type,code,name,version_number,state,content_json)
                VALUES (:id,'COMMISSION_TEMPLATE','TEST-COMMISSION-TEMPLATE','旧版委托模板',1,'PUBLISHED',
                  CAST('{"source":"legacy-placeholder","enabled":true}' AS jsonb))
                """).param("id", placeholder).update();
        List<UUID> candidates = jdbc.sql("""
                SELECT configuration.id FROM configuration_version configuration
                WHERE configuration.configuration_type='COMMISSION_TEMPLATE' AND configuration.state='PUBLISHED'
                  AND configuration.content_json->>'rendererVersion'='1'
                  AND jsonb_typeof(configuration.content_json->'blocks')='array'
                  AND jsonb_array_length(CASE
                    WHEN jsonb_typeof(configuration.content_json->'blocks')='array'
                    THEN configuration.content_json->'blocks' ELSE '[]'::jsonb END) BETWEEN 1 AND 200
                  AND NOT EXISTS (
                    SELECT 1 FROM jsonb_array_elements(CASE
                    WHEN jsonb_typeof(configuration.content_json->'blocks')='array'
                    THEN configuration.content_json->'blocks' ELSE '[]'::jsonb END) block
                    WHERE block->>'type' NOT IN ('HEADING','FIELD')
                      OR NOT jsonb_exists(block,'label')
                      OR length(trim(block->>'label')) NOT BETWEEN 1 AND 120
                      OR (block->>'type'='FIELD' AND NOT jsonb_exists(block,'key'))
                      OR (block->>'type'='FIELD' AND block->>'key' NOT IN
                        ('number','precommissionNumber','projectNumber','projectName','customerName',
                         'contactName','contactPhone','commissionedOn','dueOn','state','testingPlace',
                         'detectionProjects','projectPart','monomer','remark'))
                  )
                ORDER BY configuration.version_number DESC,configuration.id DESC LIMIT 1
                """).query(UUID.class).list();
        assertThat(candidates).isEmpty();
    }

    @Test
    void foundationRecordsEnforceDatesAndOneProjectTeamLeader() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject("SELECT id FROM department WHERE organization_id=? LIMIT 1", UUID.class, organization);
        UUID person = UUID.randomUUID();
        UUID secondPerson = UUID.randomUUID();
        UUID thirdPerson = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                person, department, "CONSTRAINT-" + person.toString().substring(0, 8), "约束测试人员一");
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                secondPerson, department, "CONSTRAINT-" + secondPerson.toString().substring(0, 8), "约束测试人员二");
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                thirdPerson, department, "CONSTRAINT-" + thirdPerson.toString().substring(0, 8), "约束测试人员三");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?,?)
                """, actor, person, organization, department, "constraint-" + actor, "not-a-login-hash", "约束测试账号");
        UUID team = UUID.randomUUID();
        UUID targetTeam = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO project_team(id,organization_id,name,created_by) VALUES (?,?,?,?)",
                    team, organization, "并发与约束测试组", actor);
            jdbc.update("INSERT INTO project_team_member(team_id,person_id,duty,leader) VALUES (?,?,?,true)",
                    team, person, "负责人");
            jdbc.update("INSERT INTO project_team_member(team_id,person_id,duty,leader,sort_order) VALUES (?,?,?,false,?)",
                    team, secondPerson, "检测成员", 1);
            jdbc.update("INSERT INTO project_team(id,organization_id,name,created_by) VALUES (?,?,?,?)",
                    targetTeam, organization, "跨组移动目标组", actor);
            jdbc.update("INSERT INTO project_team_member(team_id,person_id,duty,leader) VALUES (?,?,?,true)",
                    targetTeam, thirdPerson, "负责人");
        });
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE project_team_member SET leader=true WHERE team_id=? AND person_id=?",
                team, secondPerson)).hasMessageContaining("project_team_one_leader_ix");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE project_team_member SET duty='' WHERE team_id=? AND person_id=?", team, secondPerson))
                .hasMessageContaining("project_team_member_duty_check");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE project_team_member SET sort_order=-1 WHERE team_id=? AND person_id=?", team, secondPerson))
                .hasMessageContaining("project_team_member_sort_order_check");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> jdbc.update(
                "DELETE FROM project_team_member WHERE team_id=? AND person_id=?", team, person)))
                .rootCause().hasMessageContaining("project team must contain members and exactly one leader");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE project_team_member
                SET team_id=?,leader=false,duty=?,sort_order=?
                WHERE team_id=? AND person_id=?
                """, targetTeam, "调入成员", 1, team, person)))
                .rootCause().hasMessageContaining("project team must contain members and exactly one leader");

        String certificateNumber = "TITLE-" + UUID.randomUUID().toString().substring(0, 8);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO person_title_record(id,organization_id,person_id,issued_on,certificate_number,
                  specialty_name,issuing_authority,title_level,specialty_category,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), organization, person, LocalDate.now(), " ", "水利工程", "测试发证单位",
                "ENGINEER", "WATER_CONSERVANCY", actor)).hasMessageContaining("person_title_certificate_number_check");
        UUID personnelTitle = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO person_title_record(id,organization_id,person_id,issued_on,certificate_number,
                  specialty_name,issuing_authority,title_level,specialty_category,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, personnelTitle, organization, person, LocalDate.now(), certificateNumber, "水利工程", "测试发证单位",
                "ENGINEER", "WATER_CONSERVANCY", actor);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO person_title_record(id,organization_id,person_id,issued_on,certificate_number,
                  specialty_name,issuing_authority,title_level,specialty_category,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), organization, secondPerson, LocalDate.now(), certificateNumber.toLowerCase(),
                "结构工程", "测试发证单位", "ENGINEER", "STRUCTURAL", actor))
                .hasMessageContaining("person_title_certificate_ci_ux");
        jdbc.update("UPDATE person_title_record SET state='SUBMITTED',version=version+1 WHERE id=?", personnelTitle);
        assertThatThrownBy(() -> jdbc.update("UPDATE person_title_record SET specialty_name='越权覆盖' WHERE id=?", personnelTitle))
                .hasMessageContaining("submitted personnel title content is immutable");
        assertThat(jdbc.update("UPDATE person_title_record SET state='DRAFT',version=version+1 WHERE id=?", personnelTitle))
                .isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO training_record(id,organization_id,person_id,start_date,end_date,hours,organizer,
                  training_method,location,content,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), organization, person, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 19), 8,
                "测试主办方", "现场授课", "测试地址", "测试内容", actor)).hasMessageContaining("violates check constraint");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO training_record(id,organization_id,person_id,start_date,end_date,hours,organizer,
                  training_method,location,content,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), organization, person, LocalDate.of(2026, 8, 19), LocalDate.of(2026, 8, 20), 8,
                "测试主办方", "任意方式", "测试地址", "测试内容", actor))
                .hasMessageContaining("training_record_method_check");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO training_record(id,organization_id,person_id,start_date,end_date,hours,organizer,
                  training_method,assessment_result,location,content,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), organization, person, LocalDate.of(2026, 8, 19), LocalDate.of(2026, 8, 20), 8,
                "测试主办方", "现场授课", "未考核", "测试地址", "测试内容", actor))
                .hasMessageContaining("training_record_assessment_check");

        UUID training = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO training_record(id,organization_id,person_id,start_date,end_date,hours,organizer,
                  training_method,assessment_result,location,content,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, training, organization, person, LocalDate.of(2026, 8, 19), LocalDate.of(2026, 8, 20), 8,
                "测试主办方", "现场授课", "通过", "测试地址", "测试内容", actor);
        jdbc.update("UPDATE training_record SET state='VOID',version=version+1 WHERE id=?", training);
        assertThatThrownBy(() -> jdbc.update("UPDATE training_record SET content='越权覆盖' WHERE id=?", training))
                .hasMessageContaining("void training record content is immutable");
        assertThat(jdbc.update("UPDATE training_record SET state='ACTIVE',version=version+1 WHERE id=?", training))
                .isEqualTo(1);
    }

    @Test
    void organizationQualificationsAreFilterableIdempotentAtomicAndImmutable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> identity = jdbc.queryForMap("""
                SELECT u.id actor_id,u.person_id,o.id organization_id,u.department_id
                FROM app_user u JOIN organization o ON o.id=u.organization_id
                ORDER BY u.created_at,u.id LIMIT 1
                """);
        UUID actorId = (UUID) identity.get("actor_id");
        UUID organizationId = (UUID) identity.get("organization_id");
        UUID personId = (UUID) identity.get("person_id");
        UUID departmentId = (UUID) identity.get("department_id");
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, personId, organizationId,
                departmentId, "qualification-contract", "单位资质契约", false, 0,
                "ALL", List.of("ADMIN"), List.of("MODULE_READ", "MODULE_WRITE", "FILE_DOWNLOAD")));
        ObjectMapper json = new ObjectMapper();
        StoredFileService storedFiles = mock(StoredFileService.class);
        FoundationController foundation = new FoundationController(JdbcClient.create(dataSource), currentActor,
                mock(AuditService.class), new IdempotencyService(JdbcClient.create(dataSource), json, currentActor),
                storedFiles, json, mock(PersonnelTitleTransferService.class));

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LocalDate issuedOn = LocalDate.of(2026, 8, 20);
        UUID originalFileId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO stored_file(id,sha256,size_bytes,media_type,original_name,storage_path,purpose,uploaded_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, originalFileId, "d".repeat(64), 128L, "application/pdf",
                "SHOWCASE-资质正本.pdf", "dd/dd/" + "d".repeat(64),
                "ORGANIZATION_QUALIFICATION_ORIGINAL", actorId);
        StoredFileService.View originalFile = new StoredFileService.View(originalFileId, "d".repeat(64), 128L,
                "application/pdf", "SHOWCASE-资质正本.pdf", "ORGANIZATION_QUALIFICATION_ORIGINAL",
                OffsetDateTime.now());
        when(storedFiles.requireCurrentUpload(originalFileId)).thenReturn(originalFile);
        when(storedFiles.require(originalFileId)).thenReturn(originalFile);
        FoundationController.OrganizationQualificationWrite firstBody =
                new FoundationController.OrganizationQualificationWrite("SHOWCASE-QUAL-" + suffix,
                        issuedOn, LocalDate.of(2029, 8, 20), "SHOWCASE 发证单位",
                        "SHOWCASE 岩土与混凝土检测范围", List.of("CONCRETE_A", "GEOTECHNICAL_B"),
                        originalFileId, null, "创建契约", null);
        String createKey = "qualification-create-" + suffix;
        FoundationController.OrganizationQualificationView first =
                foundation.createOrganizationQualification(createKey, firstBody);
        assertThat(foundation.createOrganizationQualification(createKey, firstBody)).isEqualTo(first);
        verify(storedFiles).requireCurrentUpload(originalFileId);

        FoundationController.OrganizationQualificationWrite secondBody =
                new FoundationController.OrganizationQualificationWrite("SHOWCASE-QUAL-B-" + suffix,
                        issuedOn.minusDays(1), LocalDate.of(2028, 8, 19), "SHOWCASE 发证单位",
                        "SHOWCASE 量测范围", List.of("MEASUREMENT_A"), null, null, "批量契约", null);
        FoundationController.OrganizationQualificationView second = foundation.createOrganizationQualification(
                "qualification-create-second-" + suffix, secondBody);

        FoundationController.OrganizationQualificationWrite updateBody =
                new FoundationController.OrganizationQualificationWrite(first.certificateNumber(), first.issuedOn(),
                        first.validUntil(), first.issuingAuthority(), first.testingScope() + "（已核对）",
                        first.qualificationDetails(), first.originalFileId(), null, "修改幂等契约", first.version());
        String updateKey = "qualification-update-" + suffix;
        FoundationController.OrganizationQualificationView updated =
                foundation.updateOrganizationQualification(first.id(), updateKey, updateBody);
        assertThat(foundation.updateOrganizationQualification(first.id(), updateKey, updateBody)).isEqualTo(updated);
        verify(storedFiles).require(originalFileId);

        assertThat(foundation.organizationQualifications("", "SHOWCASE-QUAL-" + suffix,
                "DRAFT", "CONCRETE_A", issuedOn, issuedOn, "", 50).items())
                .extracting(FoundationController.OrganizationQualificationView::id).containsExactly(updated.id());
        assertThat(foundation.organizationQualifications("", "SHOWCASE-QUAL-" + suffix,
                "", "", null, null, "", 50).items())
                .extracting(FoundationController.OrganizationQualificationView::id).containsExactly(updated.id());
        assertThat(foundation.organizationQualifications("", "SHOWCASE-QUAL-" + suffix,
                "DRAFT", "CONCRETE_A", issuedOn.plusDays(1), issuedOn.plusDays(2), "", 50).items()).isEmpty();
        assertThatThrownBy(() -> foundation.organizationQualifications("", "", "UNKNOWN", "",
                null, null, "", 50)).isInstanceOf(ApiException.class)
                .hasMessageContaining("提交状态筛选无效");
        assertThatThrownBy(() -> foundation.organizationQualifications("", "", "", "CONCRETE_X",
                null, null, "", 50)).isInstanceOf(ApiException.class)
                .hasMessageContaining("资质详情筛选无效");
        assertThatThrownBy(() -> foundation.organizationQualifications("", "", "", "",
                issuedOn, issuedOn.minusDays(1), "", 50)).isInstanceOf(ApiException.class)
                .hasMessageContaining("结束时间不能早于开始时间");

        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        FoundationController.OrganizationQualificationBatchCommand staleBatch =
                new FoundationController.OrganizationQualificationBatchCommand("SUBMIT", List.of(
                        new FoundationController.OrganizationQualificationBatchItem(updated.id(), updated.version()),
                        new FoundationController.OrganizationQualificationBatchItem(second.id(), 999L)),
                        "REFERENCE_PARITY_CONTROL", "验证整批回滚");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                foundation.transitionOrganizationQualificationBatch("qualification-stale-" + suffix, staleBatch)))
                .isInstanceOf(ApiException.class).hasMessageContaining("已被其他用户修改");
        assertThat(jdbc.queryForObject("SELECT state FROM organization_qualification_record WHERE id=?",
                String.class, updated.id())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT state FROM organization_qualification_record WHERE id=?",
                String.class, second.id())).isEqualTo("DRAFT");

        FoundationController.OrganizationQualificationBatchCommand validBatch =
                new FoundationController.OrganizationQualificationBatchCommand("SUBMIT", List.of(
                        new FoundationController.OrganizationQualificationBatchItem(updated.id(), updated.version()),
                        new FoundationController.OrganizationQualificationBatchItem(second.id(), second.version())),
                        "REFERENCE_PARITY_CONTROL", "验证整批提交");
        String batchKey = "qualification-submit-" + suffix;
        FoundationController.OrganizationQualificationBatchResult submitted = transaction.execute(status ->
                foundation.transitionOrganizationQualificationBatch(batchKey, validBatch));
        assertThat(submitted).isNotNull();
        assertThat(submitted.processed()).isEqualTo(2);
        assertThat(foundation.transitionOrganizationQualificationBatch(batchKey, validBatch)).isEqualTo(submitted);
        assertThat(jdbc.queryForList("""
                SELECT state FROM organization_qualification_record WHERE id IN (?,?) ORDER BY id
                """, String.class, updated.id(), second.id())).containsOnly("SUBMITTED");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE organization_qualification_record SET testing_scope='绕过控制器修改' WHERE id=?
                """, updated.id())).hasMessageContaining("submitted organization qualification content is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO organization_qualification_record(id,organization_id,certificate_number,issued_on,
                  valid_until,issuing_authority,testing_scope,qualification_details,created_by)
                VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?)
                """, UUID.randomUUID(), organizationId, "SHOWCASE-DUPLICATE-" + suffix, issuedOn,
                issuedOn.plusYears(1), "SHOWCASE 发证单位", "重复详情",
                "[\"CONCRETE_A\",\"CONCRETE_A\"]", actorId))
                .hasMessageContaining("organization_qualification_details_controlled_check");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO organization_qualification_record(id,organization_id,certificate_number,issued_on,
                  valid_until,issuing_authority,testing_scope,qualification_details,created_by)
                VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?)
                """, UUID.randomUUID(), organizationId, updated.certificateNumber().toLowerCase(), issuedOn,
                issuedOn.plusYears(1), "SHOWCASE 发证单位", "大小写重复编号",
                "[\"CONCRETE_A\"]", actorId))
                .hasMessageContaining("organization_qualification_certificate_ci_ux");

        UUID foreignOrganizationId = UUID.randomUUID();
        UUID foreignFileId = UUID.randomUUID();
        jdbc.update("INSERT INTO organization(id,code,name) VALUES (?,?,?)", foreignOrganizationId,
                "QUAL-SCOPE-" + suffix, "SHOWCASE 范围外机构");
        jdbc.update("""
                INSERT INTO stored_file(id,sha256,size_bytes,media_type,original_name,storage_path,purpose,uploaded_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, foreignFileId, "e".repeat(64), 128L, "application/pdf", "范围外资质.pdf",
                "ee/ee/" + "e".repeat(64), "ORGANIZATION_QUALIFICATION_ORIGINAL", actorId);
        jdbc.update("""
                INSERT INTO organization_qualification_record(id,organization_id,certificate_number,issued_on,
                  valid_until,issuing_authority,testing_scope,qualification_details,original_file_id,created_by)
                VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?,?)
                """, UUID.randomUUID(), foreignOrganizationId, "SHOWCASE-FOREIGN-" + suffix, issuedOn,
                issuedOn.plusYears(1), "范围外发证单位", "范围外检测范围", "[\"MEASUREMENT_A\"]",
                foreignFileId, actorId);
        StoredFileService scopedFiles = new StoredFileService(JdbcClient.create(dataSource), currentActor,
                mock(AuditService.class), java.nio.file.Path.of("/tmp/lims-qualification-scope-" + suffix),
                10L * 1024 * 1024);
        assertThatThrownBy(() -> scopedFiles.download(foreignFileId, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("不在当前机构范围内");
    }

    @Test
    void metrologyQualificationsAreFilterableIdempotentAtomicAndImmutable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> identity = jdbc.queryForMap("""
                SELECT u.id actor_id,u.person_id,o.id organization_id,u.department_id
                FROM app_user u JOIN organization o ON o.id=u.organization_id
                ORDER BY u.created_at,u.id LIMIT 1
                """);
        UUID actorId = (UUID) identity.get("actor_id");
        UUID organizationId = (UUID) identity.get("organization_id");
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, (UUID) identity.get("person_id"),
                organizationId, (UUID) identity.get("department_id"), "metrology-contract", "计量资质契约",
                false, 0, "ALL", List.of("ADMIN"),
                List.of("MODULE_READ", "MODULE_WRITE", "FILE_DOWNLOAD")));
        ObjectMapper json = new ObjectMapper();
        StoredFileService storedFiles = mock(StoredFileService.class);
        FoundationController foundation = new FoundationController(JdbcClient.create(dataSource), currentActor,
                mock(AuditService.class), new IdempotencyService(JdbcClient.create(dataSource), json, currentActor),
                storedFiles, json, mock(PersonnelTitleTransferService.class));

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LocalDate issuedOn = LocalDate.of(2026, 8, 21);
        UUID firstCertificate = UUID.randomUUID();
        UUID firstOther = UUID.randomUUID();
        UUID secondCertificate = UUID.randomUUID();
        insertStoredFile(jdbc, firstCertificate, "1".repeat(64), "METROLOGY_CERTIFICATE",
                "SHOWCASE-计量证书一.pdf", actorId);
        insertStoredFile(jdbc, firstOther, "2".repeat(64), "METROLOGY_OTHER",
                "SHOWCASE-计量其他材料.pdf", actorId);
        insertStoredFile(jdbc, secondCertificate, "3".repeat(64), "METROLOGY_CERTIFICATE",
                "SHOWCASE-计量证书二.pdf", actorId);
        StoredFileService.View firstCertificateView = storedFileView(firstCertificate, "1".repeat(64),
                "METROLOGY_CERTIFICATE", "SHOWCASE-计量证书一.pdf");
        StoredFileService.View firstOtherView = storedFileView(firstOther, "2".repeat(64),
                "METROLOGY_OTHER", "SHOWCASE-计量其他材料.pdf");
        StoredFileService.View secondCertificateView = storedFileView(secondCertificate, "3".repeat(64),
                "METROLOGY_CERTIFICATE", "SHOWCASE-计量证书二.pdf");
        when(storedFiles.requireCurrentUpload(firstCertificate)).thenReturn(firstCertificateView);
        when(storedFiles.requireCurrentUpload(firstOther)).thenReturn(firstOtherView);
        when(storedFiles.requireCurrentUpload(secondCertificate)).thenReturn(secondCertificateView);
        when(storedFiles.require(firstCertificate)).thenReturn(firstCertificateView);
        when(storedFiles.require(firstOther)).thenReturn(firstOtherView);

        FoundationController.MetrologyQualificationWrite firstBody =
                new FoundationController.MetrologyQualificationWrite("SHOWCASE-CMA-" + suffix,
                        issuedOn, issuedOn.plusYears(3), "SHOWCASE 计量认证发证单位",
                        firstCertificate, firstOther, "创建契约", null);
        String createKey = "metrology-create-" + suffix;
        FoundationController.MetrologyQualificationView first =
                foundation.createMetrologyQualification(createKey, firstBody);
        assertThat(foundation.createMetrologyQualification(createKey, firstBody)).isEqualTo(first);
        verify(storedFiles).requireCurrentUpload(firstCertificate);
        verify(storedFiles).requireCurrentUpload(firstOther);

        FoundationController.MetrologyQualificationWrite secondBody =
                new FoundationController.MetrologyQualificationWrite("SHOWCASE-CMA-B-" + suffix,
                        issuedOn.minusDays(1), issuedOn.plusYears(2), "SHOWCASE 第二发证单位",
                        secondCertificate, null, "批量契约", null);
        FoundationController.MetrologyQualificationView second = foundation.createMetrologyQualification(
                "metrology-create-second-" + suffix, secondBody);

        FoundationController.MetrologyQualificationWrite updateBody =
                new FoundationController.MetrologyQualificationWrite(first.certificateNumber(), first.issuedOn(),
                        first.validUntil(), first.issuingAuthority() + "（已核对）", first.certificateFileId(),
                        first.otherFileId(), "修改幂等契约", first.version());
        String updateKey = "metrology-update-" + suffix;
        FoundationController.MetrologyQualificationView updated =
                foundation.updateMetrologyQualification(first.id(), updateKey, updateBody);
        assertThat(foundation.updateMetrologyQualification(first.id(), updateKey, updateBody)).isEqualTo(updated);
        verify(storedFiles).require(firstCertificate);
        verify(storedFiles).require(firstOther);

        assertThat(foundation.metrologyQualifications("", "SHOWCASE-CMA-" + suffix, "已核对",
                "DRAFT", issuedOn, issuedOn, "", 50).items())
                .extracting(FoundationController.MetrologyQualificationView::id).containsExactly(updated.id());
        assertThat(foundation.metrologyQualifications("", "SHOWCASE-CMA-" + suffix, "不存在",
                "DRAFT", issuedOn, issuedOn, "", 50).items()).isEmpty();
        assertThatThrownBy(() -> foundation.metrologyQualifications("", "", "", "UNKNOWN",
                null, null, "", 50)).isInstanceOf(ApiException.class)
                .hasMessageContaining("提交状态筛选无效");
        assertThatThrownBy(() -> foundation.metrologyQualifications("", "", "", "",
                issuedOn, issuedOn.minusDays(1), "", 50)).isInstanceOf(ApiException.class)
                .hasMessageContaining("结束时间不能早于开始时间");

        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        FoundationController.MetrologyQualificationBatchCommand staleBatch =
                new FoundationController.MetrologyQualificationBatchCommand("SUBMIT", List.of(
                        new FoundationController.MetrologyQualificationBatchItem(updated.id(), updated.version()),
                        new FoundationController.MetrologyQualificationBatchItem(second.id(), 999L)),
                        "REFERENCE_PARITY_CONTROL", "验证计量资质整批回滚");
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                foundation.transitionMetrologyQualificationBatch("metrology-stale-" + suffix, staleBatch)))
                .isInstanceOf(ApiException.class).hasMessageContaining("已被其他用户修改");
        assertThat(jdbc.queryForList("""
                SELECT state FROM metrology_qualification_record WHERE id IN (?,?) ORDER BY id
                """, String.class, updated.id(), second.id())).containsOnly("DRAFT");

        FoundationController.MetrologyQualificationBatchCommand validBatch =
                new FoundationController.MetrologyQualificationBatchCommand("SUBMIT", List.of(
                        new FoundationController.MetrologyQualificationBatchItem(updated.id(), updated.version()),
                        new FoundationController.MetrologyQualificationBatchItem(second.id(), second.version())),
                        "REFERENCE_PARITY_CONTROL", "验证计量资质整批提交");
        String batchKey = "metrology-submit-" + suffix;
        FoundationController.MetrologyQualificationBatchResult submitted = transaction.execute(ignored ->
                foundation.transitionMetrologyQualificationBatch(batchKey, validBatch));
        assertThat(submitted).isNotNull();
        assertThat(submitted.processed()).isEqualTo(2);
        assertThat(foundation.transitionMetrologyQualificationBatch(batchKey, validBatch)).isEqualTo(submitted);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE metrology_qualification_record SET issuing_authority='绕过控制器修改' WHERE id=?
                """, updated.id())).hasMessageContaining("submitted metrology qualification content is immutable");

        UUID uniqueCertificate = UUID.randomUUID();
        insertStoredFile(jdbc, uniqueCertificate, "4".repeat(64), "METROLOGY_CERTIFICATE",
                "SHOWCASE-大小写唯一.pdf", actorId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO metrology_qualification_record(id,organization_id,certificate_number,issued_on,
                  valid_until,issuing_authority,certificate_file_id,created_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), organizationId, updated.certificateNumber().toLowerCase(), issuedOn,
                issuedOn.plusYears(1), "SHOWCASE 发证单位", uniqueCertificate, actorId))
                .hasMessageContaining("metrology_qualification_certificate_ci_ux");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO metrology_qualification_record(id,organization_id,certificate_number,issued_on,
                  valid_until,issuing_authority,certificate_file_id,created_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), organizationId, "SHOWCASE-CMA-FILE-" + suffix, issuedOn,
                issuedOn.plusYears(1), "SHOWCASE 发证单位", firstCertificate, actorId))
                .hasMessageContaining("metrology_qualification_certificate_file_ix");

        UUID foreignOrganizationId = UUID.randomUUID();
        UUID foreignCertificate = UUID.randomUUID();
        jdbc.update("INSERT INTO organization(id,code,name) VALUES (?,?,?)", foreignOrganizationId,
                "METROLOGY-SCOPE-" + suffix, "SHOWCASE 计量范围外机构");
        insertStoredFile(jdbc, foreignCertificate, "5".repeat(64), "METROLOGY_CERTIFICATE",
                "SHOWCASE-范围外计量证书.pdf", actorId);
        jdbc.update("""
                INSERT INTO metrology_qualification_record(id,organization_id,certificate_number,issued_on,
                  valid_until,issuing_authority,certificate_file_id,created_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), foreignOrganizationId, "SHOWCASE-CMA-FOREIGN-" + suffix, issuedOn,
                issuedOn.plusYears(1), "范围外发证单位", foreignCertificate, actorId);
        StoredFileService scopedFiles = new StoredFileService(JdbcClient.create(dataSource), currentActor,
                mock(AuditService.class), java.nio.file.Path.of("/tmp/lims-metrology-scope-" + suffix),
                10L * 1024 * 1024);
        assertThatThrownBy(() -> scopedFiles.download(foreignCertificate, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("不在当前机构范围内");
    }

    @Test
    void sampleShelvesAreTwoLevelIdempotentScopedAndProduceRealQrCodes() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> identity = jdbc.queryForMap("""
                SELECT u.id actor_id,u.person_id,o.id organization_id,u.department_id
                FROM app_user u JOIN organization o ON o.id=u.organization_id
                ORDER BY u.created_at,u.id LIMIT 1
                """);
        UUID actorId = (UUID) identity.get("actor_id");
        UUID organizationId = (UUID) identity.get("organization_id");
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, (UUID) identity.get("person_id"),
                organizationId, (UUID) identity.get("department_id"), "shelf-contract", "货架契约", false, 0,
                "ALL", List.of("ADMIN"), List.of("MODULE_READ", "MODULE_WRITE", "EXPORT_DATA")));
        AuditService audit = mock(AuditService.class);
        ObjectMapper json = new ObjectMapper();
        FoundationController foundation = new FoundationController(JdbcClient.create(dataSource), currentActor,
                audit, new IdempotencyService(JdbcClient.create(dataSource), json, currentActor),
                mock(StoredFileService.class), json, mock(PersonnelTitleTransferService.class));

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String rootNumber = "SHOWCASE-SHELF-" + suffix;
        FoundationController.ShelfWrite rootBody = new FoundationController.ShelfWrite(rootNumber, 8, null, null);
        String rootKey = "shelf-root-" + suffix;
        FoundationController.ShelfView root = foundation.createShelf(rootKey, rootBody);
        assertThat(foundation.createShelf(rootKey, rootBody)).isEqualTo(root);

        FoundationController.ShelfWrite childBody = new FoundationController.ShelfWrite(
                rootNumber + "-A", 1, root.id(), null);
        FoundationController.ShelfView child = foundation.createShelf("shelf-child-" + suffix, childBody);
        assertThat(child.parentId()).isEqualTo(root.id());
        assertThat(child.parentShelfNumber()).isEqualTo(rootNumber);

        var tree = foundation.shelves(rootNumber, null, "", 50);
        assertThat(tree.total()).isEqualTo(2);
        assertThat(tree.items()).hasSize(1);
        assertThat(tree.items().getFirst().children()).extracting(FoundationController.ShelfView::id)
                .containsExactly(child.id());
        assertThat(tree.items().getFirst().childCount()).isEqualTo(1);
        assertThat(tree.items().getFirst().activeChildCount()).isEqualTo(1);

        assertThatThrownBy(() -> foundation.createShelf("shelf-grandchild-" + suffix,
                new FoundationController.ShelfWrite(rootNumber + "-A-1", 0, child.id(), null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("最多支持两级");

        FoundationController.ShelfWrite update = new FoundationController.ShelfWrite(
                child.shelfNumber() + "-UPDATED", 2, root.id(), child.version());
        String updateKey = "shelf-update-" + suffix;
        FoundationController.ShelfView updated = foundation.updateShelf(child.id(), updateKey, update);
        assertThat(foundation.updateShelf(child.id(), updateKey, update)).isEqualTo(updated);
        assertThatThrownBy(() -> foundation.updateShelf(child.id(), "shelf-update-stale-" + suffix,
                new FoundationController.ShelfWrite(updated.shelfNumber(), 3, root.id(), child.version())))
                .isInstanceOf(ApiException.class).hasMessageContaining("已被其他用户修改");
        assertThatThrownBy(() -> foundation.updateShelf(child.id(), "shelf-move-" + suffix,
                new FoundationController.ShelfWrite(updated.shelfNumber(), 3, null, updated.version())))
                .isInstanceOf(ApiException.class).hasMessageContaining("不能变更上级");

        assertThatThrownBy(() -> foundation.transitionShelf(root.id(), "shelf-root-block-" + suffix,
                new TransitionCommand("DEACTIVATE", root.version(), "REFERENCE_PARITY_CONTROL", "验证子级阻断")))
                .isInstanceOf(ApiException.class).hasMessageContaining("先停用全部下级货架");
        assertThatThrownBy(() -> foundation.transitionShelf(child.id(), "shelf-child-no-reason-" + suffix,
                new TransitionCommand("DEACTIVATE", updated.version(), "REFERENCE_PARITY_CONTROL", " ")))
                .isInstanceOf(ApiException.class).hasMessageContaining("必须填写原因");
        var childInactive = foundation.transitionShelf(child.id(), "shelf-child-off-" + suffix,
                new TransitionCommand("DEACTIVATE", updated.version(), "REFERENCE_PARITY_CONTROL", "停用下级"));
        var rootInactive = foundation.transitionShelf(root.id(), "shelf-root-off-" + suffix,
                new TransitionCommand("DEACTIVATE", root.version(), "REFERENCE_PARITY_CONTROL", "停用上级"));
        assertThatThrownBy(() -> foundation.transitionShelf(child.id(), "shelf-child-parent-off-" + suffix,
                new TransitionCommand("ACTIVATE", childInactive.version(), "REFERENCE_PARITY_CONTROL", "验证父级阻断")))
                .isInstanceOf(ApiException.class).hasMessageContaining("上级货架停用");
        var rootActive = foundation.transitionShelf(root.id(), "shelf-root-on-" + suffix,
                new TransitionCommand("ACTIVATE", rootInactive.version(), "REFERENCE_PARITY_CONTROL", "启用上级"));
        var childActive = foundation.transitionShelf(child.id(), "shelf-child-on-" + suffix,
                new TransitionCommand("ACTIVATE", childInactive.version(), "REFERENCE_PARITY_CONTROL", "启用下级"));
        assertThat(rootActive.state()).isEqualTo("ACTIVE");
        assertThat(childActive.state()).isEqualTo("ACTIVE");

        byte[] png = foundation.shelfQr(child.id()).getBody();
        assertThat(png).isNotNull();
        assertThat(png[0] & 0xff).isEqualTo(0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(decodeQr(png)).isEqualTo(child.id().toString());

        FoundationController.ShelfView collisionOne = foundation.createShelf("shelf-collision-one-" + suffix,
                new FoundationController.ShelfWrite("SHOWCASE-QR-" + suffix + "/A", 20, null, null));
        FoundationController.ShelfView collisionTwo = foundation.createShelf("shelf-collision-two-" + suffix,
                new FoundationController.ShelfWrite("SHOWCASE-QR-" + suffix + "?A", 21, null, null));
        byte[] archive = foundation.downloadShelfQrs(new FoundationController.ShelfQrDownload(
                List.of(root.id(), child.id(), collisionOne.id(), collisionTwo.id()))).getBody();
        assertThat(archive).isNotNull();
        List<String> entries = new ArrayList<>();
        Set<String> decodedIds = new HashSet<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive), java.nio.charset.StandardCharsets.UTF_8)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                entries.add(entry.getName());
                decodedIds.add(decodeQr(input.readAllBytes()));
            }
        }
        assertThat(entries).hasSize(4).doesNotHaveDuplicates()
                .contains(rootNumber + ".png", updated.shelfNumber() + ".png");
        assertThat(entries).filteredOn(name -> name.startsWith("SHOWCASE-QR-" + suffix + "_A"))
                .hasSize(2);
        assertThat(decodedIds).containsExactlyInAnyOrder(root.id().toString(), child.id().toString(),
                collisionOne.id().toString(), collisionTwo.id().toString());

        UUID foreignOrganization = UUID.randomUUID();
        UUID foreignRoot = UUID.randomUUID();
        jdbc.update("INSERT INTO organization(id,code,name) VALUES (?,?,?)", foreignOrganization,
                "SHELF-SCOPE-" + suffix, "SHOWCASE 货架范围外机构");
        jdbc.update("""
                INSERT INTO sample_shelf(id,organization_id,shelf_number,sort_order,created_by)
                VALUES (?,?,?,?,?)
                """, foreignRoot, foreignOrganization, "SHOWCASE-FOREIGN-" + suffix, 0, actorId);
        assertThatThrownBy(() -> foundation.createShelf("shelf-cross-scope-" + suffix,
                new FoundationController.ShelfWrite(rootNumber + "-FOREIGN", 0, foreignRoot, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("货架不存在");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sample_shelf(id,organization_id,parent_id,shelf_number,sort_order,created_by)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), organizationId, foreignRoot, rootNumber + "-CROSS", 0, actorId))
                .hasMessageContaining("belongs to another organization");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sample_shelf(id,organization_id,shelf_number,sort_order,created_by)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID(), organizationId, rootNumber.toLowerCase(), 0, actorId))
                .hasMessageContaining("sample_shelf_number_ci_ux");
        assertThatThrownBy(() -> foundation.downloadShelfQrs(
                new FoundationController.ShelfQrDownload(List.of(foreignRoot))))
                .isInstanceOf(ApiException.class).hasMessageContaining("货架不存在");
        verify(audit).record(eq("SAMPLE_SHELF_QR_DOWNLOADED"), eq("SAMPLE_SHELF"), eq(null), eq(null),
                any(), eq(null), eq(null));
    }

    @Test
    void dataDictionariesAreImportedAsVersionedTreesAndEnforceLifecycleRules() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> identity = jdbc.queryForMap("""
                SELECT u.id actor_id,u.person_id,u.organization_id,u.department_id
                FROM app_user u ORDER BY u.created_at,u.id LIMIT 1
                """);
        UUID actorId = (UUID) identity.get("actor_id");
        UUID organizationId = (UUID) identity.get("organization_id");
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, (UUID) identity.get("person_id"),
                organizationId, (UUID) identity.get("department_id"), "dictionary-contract", "字典契约", false, 0,
                "ALL", List.of("SYSTEM_ADMIN"), List.of("SYSTEM_ADMIN")));
        AuditService audit = mock(AuditService.class);
        JdbcClient client = JdbcClient.create(dataSource);
        DataDictionaryService dictionaries = new DataDictionaryService(client, currentActor, audit,
                new IdempotencyService(client, new ObjectMapper(), currentActor));
        assertThatThrownBy(() -> new com.lanchuan.lims.configuration.ConfigurationController(
                null, null, null, null, null).list("DICTIONARY"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("CONFIGURATION_TYPE_INVALID"));

        var imported = dictionaries.tree("水运工程混凝土抗冻试块", false);
        assertThat(imported.items()).hasSize(1);
        assertThat(imported.items().getFirst().name()).isEqualTo("样品名称");
        assertThat(imported.items().getFirst().children()).extracting(DataDictionaryService.NodeView::name)
                .containsExactly("混凝土抗冻");
        UUID descriptionRevision = jdbc.queryForObject("""
                SELECT revision.id FROM data_dictionary_revision revision
                JOIN data_dictionary_series series ON series.id=revision.series_id
                WHERE series.code='98000' AND revision.state='PUBLISHED'
                """, UUID.class);
        assertThat(dictionaries.detail(descriptionRevision).values()).extracting(DataDictionaryService.ValueView::value)
                .containsExactly("无缺角少楞，表面平整、光洁，尺寸符合标准要求", "粉状，无结块。", "液态，无不溶解沉淀物。");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var leafCommand = new DataDictionaryService.CreateCommand(null, "SHOWCASE 字典 " + suffix, false);
        String leafKey = "dictionary-leaf-" + suffix;
        var leaf = dictionaries.create(leafKey, leafCommand);
        assertThat(dictionaries.create(leafKey, leafCommand)).isEqualTo(leaf);
        String updateKey = "dictionary-update-" + suffix;
        var updated = dictionaries.update(leaf.revisionId(), updateKey, new DataDictionaryService.UpdateCommand(
                leaf.name(), false, List.of(
                new DataDictionaryService.ValueCommand(2, "第二项", "备注"),
                new DataDictionaryService.ValueCommand(1, "第一项", "")), leaf.version()));
        assertThat(dictionaries.update(leaf.revisionId(), updateKey, new DataDictionaryService.UpdateCommand(
                leaf.name(), false, List.of(
                new DataDictionaryService.ValueCommand(2, "第二项", "备注"),
                new DataDictionaryService.ValueCommand(1, "第一项", "")), leaf.version()))).isEqualTo(updated);
        assertThat(updated.values()).extracting(DataDictionaryService.ValueView::value)
                .containsExactly("第一项", "第二项");
        assertThatThrownBy(() -> dictionaries.update(leaf.revisionId(), "dictionary-stale-update-" + suffix,
                new DataDictionaryService.UpdateCommand(
                leaf.name(), false, List.of(), leaf.version())))
                .isInstanceOf(ApiException.class).hasMessageContaining("已被其他用户更新");
        var published = dictionaries.transition(leaf.revisionId(), "dictionary-publish-" + suffix,
                new TransitionCommand("PUBLISH", updated.version(), "DICTIONARY_PUBLISH", "发布契约字典"));
        assertThat(published.state()).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> dictionaries.update(leaf.revisionId(), "dictionary-published-update-" + suffix,
                new DataDictionaryService.UpdateCommand(
                leaf.name(), false, List.of(), published.version())))
                .isInstanceOf(ApiException.class).hasMessageContaining("必须先创建修订");
        var revised = dictionaries.revise(leaf.id(), "dictionary-revise-" + suffix);
        assertThat(revised.state()).isEqualTo("DRAFT");
        assertThat(revised.revisionNumber()).isEqualTo(2);
        assertThat(revised.values()).extracting(DataDictionaryService.ValueView::value)
                .containsExactly("第一项", "第二项");

        var branch = dictionaries.create("dictionary-branch-" + suffix,
                new DataDictionaryService.CreateCommand(null, "SHOWCASE 分组 " + suffix, true));
        var unpublishedChild = dictionaries.create("dictionary-unpublished-child-" + suffix,
                new DataDictionaryService.CreateCommand(branch.id(), "SHOWCASE 未发布子级 " + suffix, false));
        assertThatThrownBy(() -> dictionaries.transition(unpublishedChild.revisionId(),
                "dictionary-unpublished-child-publish-" + suffix,
                new TransitionCommand("PUBLISH", unpublishedChild.version(), "DICTIONARY_PUBLISH", "验证上级发布门禁")))
                .isInstanceOf(ApiException.class).hasMessageContaining("上级字典必须先发布");
        dictionaries.transition(branch.revisionId(), "dictionary-branch-publish-" + suffix,
                new TransitionCommand("PUBLISH", branch.version(), "DICTIONARY_PUBLISH", "发布分组"));
        var unpublishedChildPublished = dictionaries.transition(unpublishedChild.revisionId(),
                "dictionary-unpublished-child-publish-after-parent-" + suffix,
                new TransitionCommand("PUBLISH", unpublishedChild.version(), "DICTIONARY_PUBLISH",
                        "上级发布后发布子级"));
        var child = dictionaries.create("dictionary-child-" + suffix,
                new DataDictionaryService.CreateCommand(branch.id(), "SHOWCASE 子级 " + suffix, false));
        var childPublished = dictionaries.transition(child.revisionId(), "dictionary-child-publish-" + suffix,
                new TransitionCommand("PUBLISH", child.version(), "DICTIONARY_PUBLISH", "发布子级"));
        var branchCurrent = dictionaries.tree("SHOWCASE 分组 " + suffix, false).items().getFirst();
        long branchVersionWithChild = branchCurrent.version();
        assertThatThrownBy(() -> dictionaries.transition(branch.revisionId(), "dictionary-branch-block-" + suffix,
                new TransitionCommand("RETIRE", branchVersionWithChild, "DICTIONARY_RETIRE", "验证子级阻断")))
                .isInstanceOf(ApiException.class).hasMessageContaining("先停用全部子级字典");
        dictionaries.transition(child.revisionId(), "dictionary-child-retire-" + suffix,
                new TransitionCommand("RETIRE", childPublished.version(), "DICTIONARY_RETIRE", "停用子级"));
        dictionaries.transition(unpublishedChild.revisionId(),
                "dictionary-unpublished-child-retire-" + suffix,
                new TransitionCommand("RETIRE", unpublishedChildPublished.version(), "DICTIONARY_RETIRE",
                        "停用门禁验收子级"));
        branchCurrent = dictionaries.tree("SHOWCASE 分组 " + suffix, false).items().getFirst();
        dictionaries.transition(branch.revisionId(), "dictionary-branch-retire-" + suffix,
                new TransitionCommand("RETIRE", branchCurrent.version(), "DICTIONARY_RETIRE", "停用分组"));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO data_dictionary_value(id,revision_id,sort_order,value_text)
                VALUES (?,?,?,?)
                """, UUID.randomUUID(), descriptionRevision, 99, "绕过服务写入"))
                .hasMessageContaining("published data dictionary values are immutable");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO data_dictionary_series(id,organization_id,code,parent_id,sort_order,created_by)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), organizationId, "SHOWCASE-GRANDCHILD-" + suffix,
                child.id(), 0, actorId)).hasMessageContaining("at most two levels");
        verify(audit).record(eq("DATA_DICTIONARY_UPDATED"), eq("DATA_DICTIONARY"), eq(leaf.id()),
                any(), any(), eq(null), eq(null));
    }

    @Test
    void documentTemplateControlsPreserveAllVisibleRowsAndFreezeReportSnapshots() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> identity = jdbc.queryForMap("""
                SELECT u.id actor_id,u.person_id,u.organization_id,u.department_id
                FROM app_user u ORDER BY u.created_at,u.id LIMIT 1
                """);
        UUID actorId = (UUID) identity.get("actor_id");
        UUID organizationId = (UUID) identity.get("organization_id");
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, (UUID) identity.get("person_id"),
                organizationId, (UUID) identity.get("department_id"), "report-control-contract",
                "报告版本契约", false, 0, "ALL", List.of("SYSTEM_ADMIN"), List.of("SYSTEM_ADMIN")));
        AuditService audit = mock(AuditService.class);
        JdbcClient client = JdbcClient.create(dataSource);
        DocumentTemplateControlService controls = new DocumentTemplateControlService(client, currentActor, audit,
                new IdempotencyService(client, new ObjectMapper(), currentActor));

        var firstPage = controls.list("REPORT", "", null, "ALL", 1, 200);
        assertThat(firstPage.total()).isEqualTo(484);
        assertThat(firstPage.items()).hasSize(200);
        assertThat(firstPage.linkedCount()).isEqualTo(330);
        assertThat(firstPage.ambiguousCount()).isEqualTo(131);
        assertThat(firstPage.sourceOnlyCount()).isEqualTo(23);
        assertThat(firstPage.configuredCount()).isZero();
        assertThat(firstPage.items()).allSatisfy(item -> {
            assertThat(item.documentType()).isEqualTo(DocumentTemplateControlService.DocumentType.REPORT);
            assertThat(item.sourceFieldCode()).isEqualTo("report_template_code");
        });
        assertThat(controls.list("REPORT", "", null, "ALL", 3, 200).items()).hasSize(84);
        var commissionPage = controls.list("COMMISSION", "", null, "ALL", 1, 200);
        var taskPage = controls.list("TASK", "", null, "ALL", 1, 200);
        assertThat(commissionPage.total()).isEqualTo(484);
        assertThat(taskPage.total()).isEqualTo(484);
        assertThat(commissionPage.items()).allSatisfy(item ->
                assertThat(item.sourceFieldCode()).isEqualTo("zswt_template_code"));
        assertThat(taskPage.items()).allSatisfy(item ->
                assertThat(item.sourceFieldCode()).isEqualTo("rws_template_code"));
        assertThat(controls.list("REPORT", "", null, "LINKED", 1, 200).total()).isEqualTo(330);
        assertThat(controls.list("REPORT", "", null, "AMBIGUOUS", 1, 200).total()).isEqualTo(131);
        assertThat(controls.list("REPORT", "", null, "SOURCE_ONLY", 1, 200).total()).isEqualTo(23);
        assertThatThrownBy(() -> controls.list("UNKNOWN", "", null, "ALL", 1, 20))
                .isInstanceOf(ApiException.class).hasMessageContaining("文档版本类型无效");
        assertThatThrownBy(() -> controls.list("REPORT", "", null, "UNKNOWN", 1, 20))
                .isInstanceOf(ApiException.class).hasMessageContaining("关联状态无效");

        DocumentTemplateControlService.ItemView row = firstPage.items().getFirst();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var command = new DocumentTemplateControlService.UpdateCommand(
                "SHOWCASE-RPT-" + suffix, "R" + suffix, row.version());
        String key = "report-control-update-" + suffix;
        var updated = controls.update(row.id(), key, command);
        assertThat(controls.update(row.id(), key, command)).isEqualTo(updated);
        assertThat(controls.list("REPORT", suffix, null, "ALL", 1, 20).items()).extracting(
                DocumentTemplateControlService.ItemView::id).containsExactly(row.id());
        assertThatThrownBy(() -> controls.update(row.id(), "report-control-stale-" + suffix,
                new DocumentTemplateControlService.UpdateCommand("stale", "stale", row.version())))
                .isInstanceOf(ApiException.class).hasMessageContaining("其他用户更新");
        var initialRestored = controls.update(row.id(), "report-control-restore-" + suffix,
                new DocumentTemplateControlService.UpdateCommand(null, null, updated.version()));
        assertThat(initialRestored.controlledNumber()).isNull();
        assertThat(initialRestored.templateVersionNumber()).isNull();
        DocumentTemplateControlService.ItemView linkedRow = firstPage.items().stream()
                .filter(item -> item.projectRevisionId() != null).findFirst().orElseThrow();
        var linkedUpdate = controls.update(linkedRow.id(), "report-control-linked-" + suffix,
                new DocumentTemplateControlService.UpdateCommand(
                        "SHOWCASE-RPT-LINKED-" + suffix, "R" + suffix, linkedRow.version()));
        UUID customer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer(id,organization_id,code,name,short_name,unit_type,commissioning_unit)
                VALUES (?,?,?,?,?,'ENTRUSTING',true)
                """, customer, organizationId, "RPT-" + suffix, "报告版本契约客户", "版本客户");
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,organization_id,customer_id,number,name,status,created_by)
                VALUES (?,?,?,?,?,'ACTIVE',?)
                """, project, organizationId, customer, "RPT-PROJECT-" + suffix,
                "SHOWCASE 报告版本工程", actorId);
        UUID commission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission(id,organization_id,project_id,customer_id,number,contact_name,
                  commissioned_on,state,created_by)
                VALUES (?,?,?,?,?,?,current_date,'REPORTING',?)
                """, commission, organizationId, project, customer, "RPT-COMMISSION-" + suffix,
                "报告版本联系人", actorId);
        UUID selection = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission_detection_selection(id,commission_id,project_revision_id,
                  project_name_snapshot,revision_number_snapshot)
                SELECT ?,?,?,?,revision_number FROM detection_project_revision WHERE id=?
                """, selection, commission, linkedRow.projectRevisionId(), linkedRow.projectName(),
                linkedRow.projectRevisionId());

        DocumentControlSnapshotService snapshots = new DocumentControlSnapshotService(client);
        var commissionControl = controls.list("COMMISSION", "", linkedRow.projectNodeId(), "LINKED", 1, 200)
                .items().stream().filter(item -> linkedRow.projectRevisionId().equals(item.projectRevisionId()))
                .findFirst().orElseThrow();
        var commissionUpdate = controls.update(commissionControl.id(), "commission-control-linked-" + suffix,
                new DocumentTemplateControlService.UpdateCommand(
                        "SHOWCASE-WT-LINKED-" + suffix, "W" + suffix, commissionControl.version()));
        snapshots.snapshotCommission(commission);
        var commissionSnapshot = jdbc.queryForMap("""
                SELECT controlled_number_snapshot,template_version_number_snapshot,control_lock_version
                FROM commission_document_control_snapshot
                WHERE commission_id=? AND commission_detection_selection_id=?
                """, commission, selection);
        assertThat(commissionSnapshot)
                .containsEntry("controlled_number_snapshot", commissionUpdate.controlledNumber())
                .containsEntry("template_version_number_snapshot", commissionUpdate.templateVersionNumber())
                .containsEntry("control_lock_version", commissionUpdate.version());
        controls.update(commissionControl.id(), "commission-control-restore-" + suffix,
                new DocumentTemplateControlService.UpdateCommand(null, null, commissionUpdate.version()));

        UUID sampleGroup = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission_sample_group(id,commission_id,client_key,name,quantity,unit)
                VALUES (?,?,?,?,1,'组')
                """, sampleGroup, commission, "task-control-" + suffix, "任务版本快照样品组");
        Map<String, Object> parameter = jdbc.queryForMap("""
                SELECT id,code,name,unit,required,default_commissioned
                FROM test_parameter WHERE project_revision_id=? AND status='PUBLISHED' AND enabled
                ORDER BY sort_order,id LIMIT 1
                """, linkedRow.projectRevisionId());
        UUID parameterSelection = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission_parameter_selection(id,detection_selection_id,parameter_id,sample_group_id,
                  parameter_code_snapshot,parameter_name_snapshot,unit_snapshot,required_snapshot,
                  default_snapshot,snapshot_json)
                VALUES (?,?,?,?,?,?,?,?,?,'{}'::jsonb)
                """, parameterSelection, selection, parameter.get("id"), sampleGroup, parameter.get("code"),
                parameter.get("name"), parameter.get("unit"), parameter.get("required"),
                parameter.get("default_commissioned"));
        UUID sample = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sample(id,commission_id,sample_group_id,number,name,received_quantity,
                  available_quantity,unit,state)
                VALUES (?,?,?,?,?,1,1,'组','HANDED_OVER')
                """, sample, commission, sampleGroup, "TASK-CONTROL-SAMPLE-" + suffix, "任务版本快照样品");
        UUID task = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_task(id,commission_id,sample_id,parameter_id,selection_id,number,state)
                VALUES (?,?,?,?,?,?,'UNASSIGNED')
                """, task, commission, sample, parameter.get("id"), parameterSelection,
                "TASK-CONTROL-" + suffix);
        var taskControl = controls.list("TASK", "", linkedRow.projectNodeId(), "LINKED", 1, 200)
                .items().stream().filter(item -> linkedRow.projectRevisionId().equals(item.projectRevisionId()))
                .findFirst().orElseThrow();
        var taskUpdate = controls.update(taskControl.id(), "task-control-linked-" + suffix,
                new DocumentTemplateControlService.UpdateCommand(
                        "SHOWCASE-RW-LINKED-" + suffix, "T" + suffix, taskControl.version()));
        snapshots.snapshotTask(task);
        var taskSnapshot = jdbc.queryForMap("""
                SELECT controlled_number_snapshot,template_version_number_snapshot,control_lock_version
                FROM task_document_control_snapshot WHERE test_task_id=?
                """, task);
        assertThat(taskSnapshot)
                .containsEntry("controlled_number_snapshot", taskUpdate.controlledNumber())
                .containsEntry("template_version_number_snapshot", taskUpdate.templateVersionNumber())
                .containsEntry("control_lock_version", taskUpdate.version());
        controls.update(taskControl.id(), "task-control-restore-" + suffix,
                new DocumentTemplateControlService.UpdateCommand(null, null, taskUpdate.version()));

        NumberingService numbering = mock(NumberingService.class);
        when(numbering.next("REPORT", "BG")).thenReturn("BG-CONTRACT-" + suffix);
        DataScopeService access = mock(DataScopeService.class);
        ReportService reports = new ReportService(client, currentActor, numbering, audit,
                new ObjectMapper(), access, mock(SigningAssetService.class),
                mock(SignatureSchemeService.class), mock(RecentPasswordService.class),
                new ReportRevisionPolicy(), snapshots);
        ReportService.View created = reports.create(new ReportService.Create(
                commission, "SHOWCASE 报告版本快照", Map.of("result", "qualified")));
        UUID firstSnapshot = jdbc.queryForObject("""
                SELECT snapshot.id FROM report_version_document_control_snapshot snapshot
                JOIN report_version version ON version.id=snapshot.report_version_id
                WHERE version.report_id=? AND version.revision=0
                """, UUID.class, created.id());
        assertThat(jdbc.queryForMap("""
                SELECT controlled_number_snapshot,template_version_number_snapshot,control_lock_version
                FROM report_version_document_control_snapshot WHERE id=?
                """, firstSnapshot)).containsEntry("controlled_number_snapshot", linkedUpdate.controlledNumber())
                .containsEntry("template_version_number_snapshot", linkedUpdate.templateVersionNumber())
                .containsEntry("control_lock_version", linkedUpdate.version());

        var restored = controls.update(linkedRow.id(), "report-control-linked-restore-" + suffix,
                new DocumentTemplateControlService.UpdateCommand(null, null, linkedUpdate.version()));
        assertThat(restored.controlledNumber()).isNull();
        assertThat(restored.templateVersionNumber()).isNull();
        jdbc.update("UPDATE report SET state='ISSUED' WHERE id=?", created.id());
        ReportService.View issued = reports.require(created.id());
        ReportService.View revised = reports.revise(created.id(),
                new ReportService.Revision(issued.version(), "CORRECTION", "验证修订快照"));
        assertThat(revised.currentRevision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM report_version_document_control_snapshot snapshot
                JOIN report_version version ON version.id=snapshot.report_version_id
                WHERE version.report_id=?
                """, Long.class, created.id())).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                SELECT snapshot.controlled_number_snapshot,snapshot.template_version_number_snapshot
                FROM report_version_document_control_snapshot snapshot
                JOIN report_version version ON version.id=snapshot.report_version_id
                WHERE version.report_id=? AND version.revision=0
                """, created.id())).containsEntry("controlled_number_snapshot", linkedUpdate.controlledNumber())
                .containsEntry("template_version_number_snapshot", linkedUpdate.templateVersionNumber());
        assertThat(jdbc.queryForMap("""
                SELECT snapshot.controlled_number_snapshot,snapshot.template_version_number_snapshot
                FROM report_version_document_control_snapshot snapshot
                JOIN report_version version ON version.id=snapshot.report_version_id
                WHERE version.report_id=? AND version.revision=1
                """, created.id())).containsEntry("controlled_number_snapshot", null)
                .containsEntry("template_version_number_snapshot", null);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE document_template_control SET project_name_snapshot='绕过服务修改' WHERE id=?
                """, row.id())).hasMessageContaining("source identity is immutable");
        UUID snapshotId = firstSnapshot;
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE report_version_document_control_snapshot SET project_name_snapshot='绕过快照修改'
                WHERE id=?
                """, snapshotId)).hasMessageContaining("snapshots are immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM commission_document_control_snapshot
                WHERE commission_id=? AND commission_detection_selection_id=?
                """, commission, selection)).hasMessageContaining("snapshots are immutable");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE task_document_control_snapshot SET project_name_snapshot='绕过任务快照修改'
                WHERE test_task_id=?
                """, task)).hasMessageContaining("snapshots are immutable");
        verify(audit, times(8)).record(eq("DOCUMENT_TEMPLATE_CONTROL_UPDATED"), eq("DOCUMENT_TEMPLATE_CONTROL"),
                any(), any(), any(), eq(null), any());
    }

    @Test
    void notificationRulesVersionRecipientsAndProjectPendingWorkIdempotently() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID organizationId = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID actorDepartment = jdbc.queryForObject("""
                SELECT id FROM department WHERE organization_id=? ORDER BY code LIMIT 1
                """, UUID.class, organizationId);
        UUID actorPerson = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name,position,application_state) VALUES (?,?,?,?,?,'VERIFIED')",
                actorPerson, actorDepartment, "NOTIFY-A-" + suffix, "消息规则管理员", "系统管理员");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name,
                  enabled,must_change_password)
                VALUES (?,?,?,?,?,'not-a-login-password','消息规则管理员',true,true)
                """, actorId, actorPerson, organizationId, actorDepartment, "notify-admin-" + suffix);
        jdbc.update("""
                INSERT INTO user_role(user_id,role_id)
                SELECT ?::uuid,id FROM app_role WHERE code='SYSTEM_ADMIN'
                """, actorId);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, actorPerson, organizationId,
                actorDepartment, "notification-contract", "消息规则契约", false, 0, "ALL",
                List.of("SYSTEM_ADMIN"), List.of("MODULE_READ", "NOTIFICATION_RULE_MANAGE")));
        AuditService audit = mock(AuditService.class);
        JdbcClient client = JdbcClient.create(dataSource);
        NotificationRuleService rules = new NotificationRuleService(client, currentActor, audit);
        IdempotencyService idempotency = new IdempotencyService(client, new ObjectMapper(), currentActor);

        List<NotificationRuleService.EventTypeView> eventTypes = rules.eventTypes();
        assertThat(eventTypes).hasSize(10).extracting(NotificationRuleService.EventTypeView::sourceKey)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(eventTypes).extracting(NotificationRuleService.EventTypeView::label).containsExactly(
                "委托单待提交流转", "任务单待派发", "任务单待接收", "任务单待检测", "报告待编制",
                "报告待校核", "报告待审核", "报告待签发", "设备检定", "样品留样清理");
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT source_manifest_sha256) FROM notification_event_type
                WHERE source_manifest_sha256='c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e'
                """, Integer.class)).isEqualTo(1);

        UUID otherDepartment = UUID.randomUUID();
        UUID otherPerson = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        jdbc.update("INSERT INTO department(id,organization_id,code,name) VALUES (?,?,?,?)",
                otherDepartment, organizationId, "NOTIFY-" + suffix, "消息范围外部门");
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name,position,application_state) VALUES (?,?,?,?,?,'VERIFIED')",
                otherPerson, otherDepartment, "NOTIFY-P-" + suffix, "消息范围外人员", "质量观察员");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name,
                  enabled,must_change_password)
                VALUES (?,?,?,?,?,'not-a-login-password','消息范围外人员',true,true)
                """, otherUser, otherPerson, organizationId, otherDepartment, "notify-" + suffix);
        String moduleReadGrant = jdbc.queryForObject("""
                SELECT catalog_key FROM permission_catalog_binding
                WHERE permission_code='MODULE_READ' AND enforcement_state='ENFORCED'
                """, String.class);
        AccessControlService accessControl = new AccessControlService(client, currentActor, audit);
        TransactionTemplate accessTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        AccessControlService.RoleView scopedRoleView = accessTransaction.execute(ignored -> accessControl.create(
                new AccessControlService.CreateRole("消息范围角色", "DEPARTMENT", "通知接收人数据范围测试",
                        List.of("PC"), List.of(moduleReadGrant))));
        UUID scopedRole = scopedRoleView.id();
        jdbc.update("INSERT INTO user_role(user_id,role_id) VALUES (?,?)", otherUser, scopedRole);

        UUID customer = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer(id,organization_id,code,name,short_name,unit_type,commissioning_unit)
                VALUES (?,?,?,?,?,'ENTRUSTING',true)
                """, customer, organizationId, "NOTIFY-C-" + suffix, "消息规则契约客户", "消息客户");
        jdbc.update("""
                INSERT INTO project(id,organization_id,customer_id,number,name,status,created_by)
                VALUES (?,?,?,?,?,'ACTIVE',?)
                """, project, organizationId, customer, "NOTIFY-PROJECT-" + suffix,
                "SHOWCASE 消息规则工程", actorId);
        UUID draftCommission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission(id,organization_id,project_id,customer_id,number,contact_name,
                  commissioned_on,state,created_by,updated_at)
                VALUES (?,?,?,?,?,?,current_date,'DRAFT',?,now()-interval '2 days')
                """, draftCommission, organizationId, project, customer, "NOTIFY-DRAFT-" + suffix,
                "消息规则联系人", actorId);
        UUID taskCommission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission(id,organization_id,project_id,customer_id,number,contact_name,
                  commissioned_on,state,created_by)
                VALUES (?,?,?,?,?,?,current_date,'ASSIGNED',?)
                """, taskCommission, organizationId, project, customer, "NOTIFY-TASK-C-" + suffix,
                "消息规则联系人", actorId);
        UUID sample = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sample(id,commission_id,number,name,received_quantity,available_quantity,unit,state)
                VALUES (?,?,?,?,1,1,'组','HANDED_OVER')
                """, sample, taskCommission, "NOTIFY-S-" + suffix, "消息规则样品");
        UUID parameter = jdbc.queryForObject("""
                SELECT id FROM test_parameter WHERE status='PUBLISHED' AND enabled ORDER BY created_at,id LIMIT 1
                """, UUID.class);
        UUID task = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_task(id,commission_id,sample_id,parameter_id,number,assigned_to,assigned_by,
                  state,updated_at)
                VALUES (?,?,?,?,?,?,?,'ASSIGNED',now()-interval '2 days')
                """, task, taskCommission, sample, parameter, "NOTIFY-T-" + suffix, actorPerson, actorId);

        var createPrimary = new NotificationRuleService.CreateCommand(
                "COMMISSION_PENDING_SUBMISSION", 0, List.of(actorPerson));
        String createKey = "notification-primary-" + suffix;
        var primary = idempotency.execute(createKey, "notification-rule-create", createPrimary,
                NotificationRuleService.RuleView.class, () -> rules.create(createPrimary));
        assertThat(idempotency.execute(createKey, "notification-rule-create", createPrimary,
                NotificationRuleService.RuleView.class, () -> rules.create(createPrimary))).isEqualTo(primary);
        var additional = rules.create(new NotificationRuleService.CreateCommand(
                "TASK_PENDING_ACCEPTANCE", 0, List.of(otherPerson)));
        assertThat(rules.list("", "ALL", 1, 20)).satisfies(page -> {
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.activeCount()).isEqualTo(2);
            assertThat(page.unconfiguredCount()).isEqualTo(8);
        });

        NotificationProjectionService projection = new NotificationProjectionService(client);
        NotificationProjectionService.ProjectionResult firstProjection = projection.projectAll();
        assertThat(firstProjection.lockAcquired()).isTrue();
        assertThat(firstProjection.deliveryCount()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification WHERE rule_series_id=? AND resource_id=? AND recipient_id=?
                """, Integer.class, primary.id(), draftCommission, actorId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification WHERE rule_series_id=? AND resource_id=? AND recipient_id=?
                """, Integer.class, additional.id(), task, actorId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification WHERE rule_series_id=? AND resource_id=? AND recipient_id=?
                """, Integer.class, additional.id(), task, otherUser)).isZero();
        assertThat(projection.projectAll().deliveryCount()).isZero();

        List.of("TASK_PENDING_ASSIGNMENT", "TASK_PENDING_TEST", "REPORT_PENDING_DRAFT",
                        "REPORT_PENDING_CHECK", "REPORT_PENDING_REVIEW", "REPORT_PENDING_ISSUE",
                        "EQUIPMENT_VERIFICATION", "SAMPLE_RETENTION_CLEANUP")
                .forEach(eventType -> rules.create(new NotificationRuleService.CreateCommand(
                        eventType, 0, List.of(actorPerson))));
        assertThat(rules.list("", "ALL", 1, 20)).satisfies(page -> {
            assertThat(page.total()).isEqualTo(10);
            assertThat(page.activeCount()).isEqualTo(10);
            assertThat(page.unconfiguredCount()).isZero();
        });
        NotificationProjectionService.ProjectionResult allBranches = projection.projectAll();
        assertThat(allBranches.lockAcquired()).isTrue();
        assertThat(allBranches.ruleCount()).isEqualTo(10);

        var revised = rules.update(primary.id(), new NotificationRuleService.UpdateCommand(
                1, List.of(actorPerson), primary.version()));
        assertThat(revised.revisionNumber()).isEqualTo(2);
        assertThat(rules.history(primary.id())).hasSize(2)
                .extracting(NotificationRuleService.RuleView::state).containsExactly("ACTIVE", "RETIRED");
        assertThatThrownBy(() -> rules.update(primary.id(), new NotificationRuleService.UpdateCommand(
                1, List.of(actorPerson), primary.version())))
                .isInstanceOf(ApiException.class).hasMessageContaining("其他用户更新");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE notification_rule_revision SET condition_days=99 WHERE id=?
                """, primary.revisionId())).hasMessageContaining("content is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM notification_rule_recipient WHERE revision_id=?
                """, primary.revisionId())).hasMessageContaining("recipients are immutable");

        var retired = rules.transition(primary.id(), new TransitionCommand("RETIRE", revised.version(),
                "NOTIFICATION_RULE_RETIRE", "契约测试停用"));
        assertThat(retired.state()).isEqualTo("RETIRED");
        var reactivated = rules.transition(primary.id(), new TransitionCommand("REACTIVATE", retired.version(),
                "NOTIFICATION_RULE_REACTIVATE", "契约测试重新启用"));
        assertThat(reactivated.state()).isEqualTo("ACTIVE");
        assertThat(rules.history(primary.id())).hasSize(3)
                .extracting(NotificationRuleService.RuleView::revisionNumber).containsExactly(3, 2, 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_event WHERE action='NOTIFICATION_PROJECTED'
                  AND resource_type='NOTIFICATION_RULE_REVISION'
                """, Integer.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void rolePolicyCatalogIsVersionedEnforcedAndSessionSafe() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcClient client = JdbcClient.create(dataSource);
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID departmentId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID actorPerson = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO person(id,department_id,employee_number,name,position,application_state)
                VALUES (?,?,?,?,?,'VERIFIED')
                """, actorPerson, departmentId, "ACCESS-" + suffix, "权限契约管理员", "系统管理员");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,
                  display_name,enabled,must_change_password)
                VALUES (?,?,?,?,?,'not-a-login-password','权限契约管理员',true,true)
                """, actorId, actorPerson, organizationId, departmentId, "access-admin-" + suffix);
        jdbc.update("""
                INSERT INTO user_role(user_id,role_id)
                SELECT ?::uuid,id FROM app_role WHERE code='SYSTEM_ADMIN'
                """, actorId);

        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, actorPerson, organizationId,
                departmentId, "access-contract", "权限契约管理员", false, 0, "ALL",
                List.of("SYSTEM_ADMIN"), List.of("SYSTEM_ADMIN")));
        AuditService audit = mock(AuditService.class);
        AccessControlService service = new AccessControlService(client, currentActor, audit);
        AccountAdministrationService accountAdministration = new AccountAdministrationService(client,
                mock(PasswordEncoder.class), mock(PasswordPolicy.class), currentActor, audit);
        IdempotencyService idempotency = new IdempotencyService(client, new ObjectMapper(), currentActor);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        AccessControlService.Catalog catalog = service.catalog();
        assertThat(catalog.sourceNodeCount()).isEqualTo(524);
        assertThat(catalog.sourceManifestSha256())
                .isEqualTo("38db336a2be5756a69ed5ce491f9dcf63b4499b5769c19da00041374e2b8720a");
        assertThat(catalog.clients()).extracting(AccessControlService.ClientCatalog::label).containsExactly(
                "机构pc管理端", "微信小程序", "委托客户PC端", "现场检测APP");
        assertThat(catalog.clients()).extracting(AccessControlService.ClientCatalog::availability)
                .containsExactly("ACTIVE", "READINESS_BLOCKED", "READINESS_BLOCKED", "READINESS_BLOCKED");
        assertThat(catalog.enforcedLeafCount()).isEqualTo(
                jdbc.queryForObject("SELECT count(*) FROM permission", Integer.class));
        assertThat(catalog.blockedLeafCount()).isGreaterThan(300);

        AccessControlService.RoleView systemAdmin = service.roles().stream()
                .filter(role -> role.code().equals("SYSTEM_ADMIN")).findFirst().orElseThrow();
        assertThat(systemAdmin.revisionNumber()).isEqualTo(1);
        assertThat(systemAdmin.permissions()).contains("SYSTEM_ADMIN");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                  (SELECT permission_code FROM role_permission WHERE role_id=?::uuid
                   EXCEPT
                   SELECT permission_code FROM role_policy_permission_grant WHERE revision_id=?::uuid)
                  UNION ALL
                  (SELECT permission_code FROM role_policy_permission_grant WHERE revision_id=?::uuid
                   EXCEPT
                   SELECT permission_code FROM role_permission WHERE role_id=?::uuid)
                ) difference
                """, Integer.class, systemAdmin.id(),
                jdbc.queryForObject("SELECT current_policy_revision_id FROM app_role WHERE id=?", UUID.class,
                        systemAdmin.id()),
                jdbc.queryForObject("SELECT current_policy_revision_id FROM app_role WHERE id=?", UUID.class,
                        systemAdmin.id()), systemAdmin.id())).isZero();

        String moduleReadGrant = jdbc.queryForObject("""
                SELECT catalog_key FROM permission_catalog_binding
                WHERE permission_code='MODULE_READ' AND enforcement_state='ENFORCED'
                """, String.class);
        String workbenchGrant = jdbc.queryForObject("""
                SELECT catalog_key FROM permission_catalog_binding
                WHERE permission_code='WORKBENCH_READ' AND enforcement_state='ENFORCED'
                """, String.class);
        String sourceBlockedGrant = jdbc.queryForObject("""
                SELECT catalog_key FROM permission_catalog_binding
                WHERE enforcement_state='BLOCKED' ORDER BY catalog_key LIMIT 1
                """, String.class);
        AccessControlService.CreateRole create = new AccessControlService.CreateRole(
                "契约观察员", "DEPARTMENT", "权限策略版本与会话撤销契约", List.of("PC"),
                List.of(moduleReadGrant));
        String idempotencyKey = "role-policy-create-" + suffix;
        AccessControlService.RoleView created = transaction.execute(ignored -> idempotency.execute(
                idempotencyKey, "access-role-create", create, AccessControlService.RoleView.class,
                () -> service.create(create)));
        AccessControlService.RoleView replayed = transaction.execute(ignored -> idempotency.execute(
                idempotencyKey, "access-role-create", create, AccessControlService.RoleView.class,
                () -> service.create(create)));
        assertThat(replayed).isEqualTo(created);
        assertThat(created).satisfies(role -> {
            assertThat(role.version()).isZero();
            assertThat(role.revisionNumber()).isEqualTo(1);
            assertThat(role.permissions()).containsExactly("MODULE_READ");
            assertThat(role.allowedActions()).containsExactly("UPDATE", "RETIRE");
        });

        UUID assignedPerson = UUID.randomUUID();
        UUID assignedUser = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO person(id,department_id,employee_number,name,position,application_state)
                VALUES (?,?,?,?,?,'VERIFIED')
                """, assignedPerson, departmentId, "ACCESS-U-" + suffix, "权限契约用户", "观察员");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,
                  display_name,enabled,must_change_password)
                VALUES (?,?,?,?,?,'not-a-login-password','权限契约用户',true,true)
                """, assignedUser, assignedPerson, organizationId, departmentId, "access-user-" + suffix);
        jdbc.update("INSERT INTO user_role(user_id,role_id) VALUES (?,?)", assignedUser, created.id());
        long sessionBefore = jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=?",
                Long.class, assignedUser);

        var executor = Executors.newSingleThreadExecutor();
        try (Connection concurrentWriter = dataSource.getConnection()) {
            concurrentWriter.setAutoCommit(false);
            try (var statement = concurrentWriter.prepareStatement(
                    "UPDATE app_user SET version=version+1 WHERE id=?")) {
                statement.setObject(1, assignedUser);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            var staleAssignment = executor.submit(() -> transaction.execute(ignored ->
                    accountAdministration.updateRoles(assignedUser, List.of(systemAdmin.id()), 0)));
            boolean waitingOnAccountVersion = false;
            for (int attempt = 0; attempt < 200 && !waitingOnAccountVersion; attempt++) {
                waitingOnAccountVersion = jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                          WHERE wait_event_type='Lock' AND query ILIKE '%UPDATE app_user SET version=version+1%')
                        """, Boolean.class);
                if (!waitingOnAccountVersion) Thread.sleep(25);
            }
            assertThat(waitingOnAccountVersion).isTrue();
            concurrentWriter.commit();
            assertThatThrownBy(() -> staleAssignment.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class)
                    .hasMessageContaining("账号已被更新");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT role_id FROM user_role WHERE user_id=? ORDER BY role_id",
                UUID.class, assignedUser)).containsExactly(created.id());

        AccessControlService.UpdateRole update = new AccessControlService.UpdateRole(created.name(), "DEPARTMENT",
                "权限策略修订后撤销所有已分配账号会话", List.of("PC"),
                List.of(moduleReadGrant, workbenchGrant), created.version());
        AccessControlService.RoleView updated = transaction.execute(ignored -> service.update(created.id(), update));
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.revisionNumber()).isEqualTo(2);
        assertThat(updated.permissions()).containsExactly("MODULE_READ", "WORKBENCH_READ");
        assertThat(jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=?", Long.class, assignedUser))
                .isEqualTo(sessionBefore + 1);
        AccountAdministrationService.ManagedAccount assigned = transaction.execute(ignored ->
                accountAdministration.updateRoles(assignedUser, List.of(created.id()), 1));
        assertThat(assigned.version()).isEqualTo(2);
        assertThat(assigned.roleIds()).containsExactly(created.id());
        assertThat(jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=?", Long.class, assignedUser))
                .isEqualTo(sessionBefore + 2);

        Map<String, Long> revokeRequest = Map.of("expectedVersion", assigned.version());
        String revokeKey = "access-user-session-revoke-" + suffix;
        AccountAdministrationService.ManagedAccount revoked = transaction.execute(ignored -> idempotency.execute(
                revokeKey, "access-user-session-revoke:" + assignedUser, revokeRequest,
                AccountAdministrationService.ManagedAccount.class,
                () -> accountAdministration.revokeSessions(assignedUser, assigned.version())));
        AccountAdministrationService.ManagedAccount revokeReplay = transaction.execute(ignored -> idempotency.execute(
                revokeKey, "access-user-session-revoke:" + assignedUser, revokeRequest,
                AccountAdministrationService.ManagedAccount.class,
                () -> accountAdministration.revokeSessions(assignedUser, assigned.version())));
        assertThat(revokeReplay).isEqualTo(revoked);
        assertThat(revoked.version()).isEqualTo(3);
        long sessionAfterRevoke = jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=?",
                Long.class, assignedUser);
        assertThat(sessionAfterRevoke).isEqualTo(sessionBefore + 3);
        assertThatThrownBy(() -> transaction.execute(ignored ->
                accountAdministration.revokeSessions(assignedUser, assigned.version())))
                .isInstanceOf(ApiException.class).hasMessageContaining("账号已被更新");
        assertThat(jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=?",
                Long.class, assignedUser)).isEqualTo(sessionAfterRevoke);
        assertThat(service.history(created.id())).extracting(AccessControlService.RoleRevisionView::revisionNumber)
                .containsExactly(2, 1);
        assertThatThrownBy(() -> transaction.execute(ignored -> service.update(created.id(), update)))
                .isInstanceOf(ApiException.class).hasMessageContaining("其他用户更新");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE role_policy_revision SET role_name='绕过修改' WHERE role_id=? AND revision_number=1
                """, created.id())).hasMessageContaining("role policy snapshots are immutable");

        assertThatThrownBy(() -> transaction.execute(ignored -> service.create(
                new AccessControlService.CreateRole("越界权限", "SELF", "来源节点不可伪装为已执行",
                        List.of("PC"), List.of(sourceBlockedGrant)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("已接入后端门禁");
        assertThatThrownBy(() -> transaction.execute(ignored -> service.create(
                new AccessControlService.CreateRole("未交付客户端", "SELF", "未交付端不可配置",
                        List.of("MINI_PROGRAM"), List.of(moduleReadGrant)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("已交付并启用");
        assertThatThrownBy(() -> transaction.execute(ignored -> service.transition(created.id(),
                new TransitionCommand("RETIRE", updated.version(), "ROLE_RETIRE", "仍有账号在用"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("仍有在用账号");

        jdbc.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", assignedUser, created.id());
        transaction.execute(ignored -> service.transition(created.id(),
                new TransitionCommand("RETIRE", updated.version(), "ROLE_RETIRE", "契约测试停用")));
        AccessControlService.RoleView retired = service.require(created.id());
        assertThat(retired.status()).isEqualTo("RETIRED");
        assertThat(retired.allowedActions()).containsExactly("REACTIVATE");
        transaction.execute(ignored -> service.transition(created.id(),
                new TransitionCommand("REACTIVATE", retired.version(), "ROLE_REACTIVATE", "契约测试恢复")));
        assertThat(service.require(created.id())).satisfies(role -> {
            assertThat(role.status()).isEqualTo("ACTIVE");
            assertThat(role.version()).isEqualTo(3);
            assertThat(role.revisionNumber()).isEqualTo(4);
        });

        String systemAdminGrant = jdbc.queryForObject("""
                SELECT catalog_key FROM permission_catalog_binding
                WHERE permission_code='SYSTEM_ADMIN' AND enforcement_state='ENFORCED'
                """, String.class);
        assertThatThrownBy(() -> transaction.execute(ignored -> service.update(systemAdmin.id(),
                new AccessControlService.UpdateRole(systemAdmin.name(), systemAdmin.dataScope(),
                        systemAdmin.description(), systemAdmin.clientCodes(),
                        systemAdmin.catalogGrantKeys().stream().filter(key -> !key.equals(systemAdminGrant)).toList(),
                        systemAdmin.version()))))
                .isInstanceOf(ApiException.class).hasMessageContaining("必须保留系统管理权限");
        verify(audit, times(4)).record(any(), eq("ROLE"), any(),
                org.mockito.ArgumentMatchers.nullable(Object.class), any(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class));
    }

    private String decodeQr(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        RGBLuminanceSource source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels);
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }

    private void insertStoredFile(JdbcTemplate jdbc, UUID id, String sha256, String purpose,
                                  String originalName, UUID actorId) {
        jdbc.update("""
                INSERT INTO stored_file(id,sha256,size_bytes,media_type,original_name,storage_path,purpose,uploaded_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, sha256, 128L, "application/pdf", originalName,
                sha256.substring(0, 2) + "/" + sha256.substring(2, 4) + "/" + sha256, purpose, actorId);
    }

    private StoredFileService.View storedFileView(UUID id, String sha256, String purpose, String originalName) {
        return new StoredFileService.View(id, sha256, 128L, "application/pdf", originalName, purpose,
                OffsetDateTime.now());
    }

    @Test
    void personnelQueriesHonorDepartmentDataScope() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID ownDepartment = UUID.randomUUID();
        UUID otherDepartment = UUID.randomUUID();
        UUID ownPerson = UUID.randomUUID();
        UUID otherPerson = UUID.randomUUID();
        jdbc.update("INSERT INTO department(id,organization_id,code,name) VALUES (?,?,?,?)",
                ownDepartment, organization, "SCOPE-" + ownDepartment.toString().substring(0, 8), "范围内部门");
        jdbc.update("INSERT INTO department(id,organization_id,code,name) VALUES (?,?,?,?)",
                otherDepartment, organization, "SCOPE-" + otherDepartment.toString().substring(0, 8), "范围外部门");
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                ownPerson, ownDepartment, "SCOPE-" + ownPerson.toString().substring(0, 8), "范围内人员");
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                otherPerson, otherDepartment, "SCOPE-" + otherPerson.toString().substring(0, 8), "范围外人员");

        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), ownPerson,
                organization, ownDepartment, "scope-test", "范围测试", false, 0,
                "DEPARTMENT", List.of("TESTER"), List.of("MODULE_READ")));
        PersonnelService service = new PersonnelService(JdbcClient.create(dataSource), currentActor,
                mock(AuditService.class), new PersonnelSensitiveDataService(""),
                mock(AccountAdministrationService.class), mock(StoredFileService.class));

        List<UUID> visible = service.list("", "", "", "范围", "", null, 100)
                .items().stream().map(PersonnelService.PersonnelSummary::id).toList();
        assertThat(visible).contains(ownPerson).doesNotContain(otherPerson);
        assertThatThrownBy(() -> service.require(otherPerson)).hasMessageContaining("当前数据范围");
        assertThat(service.options().departments()).extracting(PersonnelService.DepartmentOption::id)
                .containsExactly(ownDepartment);

        UUID createdBy = jdbc.queryForObject("SELECT id FROM app_user WHERE organization_id=? ORDER BY created_at LIMIT 1",
                UUID.class, organization);
        UUID ownTitle = UUID.randomUUID();
        UUID otherTitle = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO person_title_record(id,organization_id,person_id,issued_on,certificate_number,
                  specialty_name,issuing_authority,title_level,specialty_category,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, ownTitle, organization, ownPerson, LocalDate.now(), "SCOPE-TITLE-" + ownTitle,
                "范围内专业", "范围内发证单位", "ENGINEER", "WATER_CONSERVANCY", createdBy);
        jdbc.update("""
                INSERT INTO person_title_record(id,organization_id,person_id,issued_on,certificate_number,
                  specialty_name,issuing_authority,title_level,specialty_category,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, otherTitle, organization, otherPerson, LocalDate.now(), "SCOPE-TITLE-" + otherTitle,
                "范围外专业", "范围外发证单位", "ENGINEER", "WATER_CONSERVANCY", createdBy);
        FoundationController foundation = new FoundationController(JdbcClient.create(dataSource), currentActor,
                mock(AuditService.class), mock(IdempotencyService.class), mock(StoredFileService.class),
                new ObjectMapper(), mock(PersonnelTitleTransferService.class));
        assertThat(foundation.titles("", "", "", "", null, null, "", "", "", null, "", 100)
                .items()).extracting(FoundationController.TitleView::id).contains(ownTitle).doesNotContain(otherTitle);
        assertThat(foundation.titleDetail(ownTitle).id()).isEqualTo(ownTitle);
        assertThatThrownBy(() -> foundation.titleDetail(otherTitle)).hasMessageContaining("职称记录不存在");
    }

    @Test
    void personnelTitleImportValidatesEntireWorkbookBeforeCreatingRows() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        var person = jdbc.queryForMap("""
                SELECT p.id,p.employee_number,p.department_id FROM person p JOIN department d ON d.id=p.department_id
                WHERE d.organization_id=? AND p.active ORDER BY p.created_at LIMIT 1
                """, organization);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), (UUID) person.get("id"),
                organization, (UUID) person.get("department_id"), "title-import-test", "职称导入测试", false, 0,
                "ALL", List.of("ADMIN"), List.of("MODULE_READ", "MODULE_WRITE", "PERSON_IMPORT")));
        PersonnelTitleTransferService service = new PersonnelTitleTransferService(JdbcClient.create(dataSource),
                currentActor, mock(AuditService.class), mock(StoredFileService.class));
        AtomicInteger created = new AtomicInteger();
        String employeeNumber = String.valueOf(person.get("employee_number"));
        assertThatThrownBy(() -> service.importWorkbook(
                titleWorkbook(employeeNumber, "FORMULA-" + UUID.randomUUID(), true), row -> {
                    created.incrementAndGet();
                    return UUID.randomUUID();
                })).hasMessageContaining("只接受原子值");
        assertThat(created).hasValue(0);

        PersonnelTitleTransferService.ImportResult result = service.importWorkbook(
                titleWorkbook(employeeNumber, "VALID-" + UUID.randomUUID(), false), row -> {
                    created.incrementAndGet();
                    return UUID.randomUUID();
                });
        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(result.sha256()).hasSize(64);
        assertThat(created).hasValue(1);
    }

    @Test
    void referenceConfigurationImportIsCompleteAndRepeatable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_catalog_node WHERE source_system='showcase_catalog'", Integer.class)).isEqualTo(513);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM detection_project_revision revision
                JOIN detection_catalog_node node ON node.id=revision.project_node_id
                WHERE node.source_system='showcase_catalog'
                """, Integer.class)).isEqualTo(448);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM detection_project_revision revision
                JOIN detection_catalog_node node ON node.id=revision.project_node_id
                WHERE revision.status='PUBLISHED' AND node.status='ACTIVE'
                  AND COALESCE(NULLIF(node.symbol,''),NULLIF(node.source_record_id,''),node.source_key) IS NULL
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_parameter WHERE source_system='showcase_catalog'", Integer.class)).isEqualTo(3080);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_standard", Integer.class)).isEqualTo(713);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_standard WHERE state='PUBLISHED'", Integer.class)).isEqualTo(713);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_standard WHERE status='CURRENT'", Integer.class)).isEqualTo(692);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM numbering_flow_rule", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM numbering_flow_rule_pattern", Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM numbering_flow_template", Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM numbering_flow_rule_project", Integer.class)).isEqualTo(560);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_parameter_standard", Integer.class)).isEqualTo(10227);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT standard_id) FROM test_parameter_standard", Integer.class)).isEqualTo(197);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM detection_standard WHERE source_key IN ('--------','SL  713-2015')
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM detection_standard WHERE source_validation_warning IS NOT NULL
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM detection_catalog_node child
                LEFT JOIN detection_catalog_node parent ON parent.id=child.parent_id
                WHERE child.parent_id IS NOT NULL AND parent.id IS NULL
                """, Integer.class)).isZero();
        assertThat(Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
                .migrationsExecuted).isZero();
    }

    @Test
    void administrativeDivisionsAreHierarchicalAndRejectInvalidPaths() {
        AdministrativeDivisionService service = new AdministrativeDivisionService(
                JdbcClient.create(dataSource), new ObjectMapper());
        assertThat(service.children("", "")).hasSize(34)
                .extracting(AdministrativeDivisionService.Option::name).contains("海南省", "北京市");
        assertThat(service.children("海南省", "")).hasSize(18)
                .extracting(AdministrativeDivisionService.Option::name).contains("海口市", "三亚市", "儋州市");
        assertThat(service.children("海南省", "海口市"))
                .extracting(AdministrativeDivisionService.Option::name)
                .containsExactly("秀英区", "龙华区", "琼山区", "美兰区");
        service.validate("海南省", "海口市", "美兰区");
        assertThatThrownBy(() -> service.validate("海南省", "海口市", "不存在区"))
                .hasMessageContaining("不属于当前已发布行政区划");
        assertThatThrownBy(() -> service.validate("", "海口市", ""))
                .hasMessageContaining("不能脱离省级行政区");
    }

    private static byte[] titleWorkbook(String employeeNumber, String certificateNumber,
                                        boolean certificateFormula) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("人员职称导入");
            var header = sheet.createRow(0);
            String[] headers = {"工号", "发证时间", "职称证书编号", "专业名称", "发证单位", "职称等级", "专业类型", "备注"};
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(employeeNumber);
            row.createCell(1).setCellValue("2026-08-20");
            if (certificateFormula) row.createCell(2).setCellFormula("\"" + certificateNumber + "\"");
            else row.createCell(2).setCellValue(certificateNumber);
            row.createCell(3).setCellValue("水利工程");
            row.createCell(4).setCellValue("受控测试发证单位");
            row.createCell(5).setCellValue("工程师");
            row.createCell(6).setCellValue("水利");
            row.createCell(7).setCellValue("SHOWCASE 受控导入测试");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    @Test
    void concurrentNumberAllocationIsUnique() throws Exception {
        NumberingService service = new NumberingService(JdbcClient.create(dataSource));
        List<Callable<String>> calls = new ArrayList<>();
        for (int index = 0; index < 40; index++) calls.add(() -> service.next("CONCURRENCY_TEST", "QA"));
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<String> numbers = pool.invokeAll(calls).stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).toList();
            assertThat(new HashSet<>(numbers)).hasSize(40);
        }
    }

    @Test
    void controlledBusinessAndTemplateNumberingAreVersionedAtomicAndDriveRealGeneration() throws Exception {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        Map<String, Object> identity = sql.queryForMap("""
                SELECT id actor_id,person_id,organization_id,department_id,username,display_name
                FROM app_user WHERE organization_id IS NOT NULL ORDER BY created_at,id LIMIT 1
                """);
        UUID actorId = (UUID) identity.get("actor_id");
        UUID organization = (UUID) identity.get("organization_id");
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, (UUID) identity.get("person_id"),
                organization, (UUID) identity.get("department_id"), (String) identity.get("username"),
                (String) identity.get("display_name"), false, 0, "ALL", List.of("SYSTEM_ADMIN"),
                List.of("MODULE_READ", "MODULE_WRITE", "SYSTEM_ADMIN")));
        AuditService audit = mock(AuditService.class);
        NumberPatternRenderer renderer = new NumberPatternRenderer();
        BusinessNumberingConfigurationService configuration = new BusinessNumberingConfigurationService(
                JdbcClient.create(dataSource), currentActor, audit, renderer);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        BusinessNumberingConfigurationService.RuleWrite first =
                new BusinessNumberingConfigurationService.RuleWrite("CDX501", "PROJECT", "SHOWCASE 工程编号",
                        "PX-", "NUMBER_START", "Y", "-R1", "NUMBER_END", 4, 9, 0);
        var created = transaction.execute(status -> configuration.createRule(first));
        assertThat(created).isNotNull();
        assertThat(created.nextSequence()).isEqualTo(9);
        assertThat(created.nextPreview()).matches("PX-\\d{4}0009-R1");
        assertThat(configuration.preview(first, 25, LocalDate.of(2026, 8, 21)))
                .isEqualTo("PX-20260025-R1");
        var publishedTransition = transaction.execute(status -> configuration.transitionRule(created.id(),
                new TransitionCommand("PUBLISH", created.version(), "CONTROLLED_RELEASE", "SHOWCASE 发布验证")));
        assertThat(publishedTransition).isNotNull();
        var published = configuration.rule(created.id());
        assertThat(published.state()).isEqualTo("PUBLISHED");

        NumberingService generator = new NumberingService(JdbcClient.create(dataSource), currentActor, renderer);
        assertThat(generator.next("PROJECT", "GC")).isEqualTo("PX-20260009-R1");
        assertThat(generator.next("PROJECT", "GC")).isEqualTo("PX-20260010-R1");
        var reset = transaction.execute(status -> configuration.resetSequence(published.id(), published.version(),
                25, "MANUAL_SEQUENCE_RESET", "SHOWCASE 重置验证"));
        assertThat(reset).isNotNull();
        assertThat(generator.next("PROJECT", "GC")).isEqualTo("PX-20260025-R1");

        var revision = transaction.execute(status -> configuration.reviseRule(reset.id(), reset.version()));
        assertThat(revision).isNotNull();
        var revisedWrite = new BusinessNumberingConfigurationService.RuleWrite("CDX501", "PROJECT",
                "SHOWCASE 工程编号 R2", "PX-", "NUMBER_START", "Y", "-R2", "NUMBER_END", 4, 9,
                revision.version());
        var revised = transaction.execute(status -> configuration.updateRule(revision.id(), revisedWrite));
        assertThat(revised).isNotNull();
        assertThatThrownBy(() -> transaction.execute(status -> configuration.updateRule(revision.id(),
                new BusinessNumberingConfigurationService.RuleWrite("CDX501", "PROJECT", "陈旧更新", "PX-",
                        "NUMBER_START", "Y", "-R2", "NUMBER_END", 4, 9, revision.version()))))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("VERSION_CONFLICT"));
        transaction.execute(status -> configuration.transitionRule(revised.id(),
                new TransitionCommand("PUBLISH", revised.version(), "CONTROLLED_RELEASE", "SHOWCASE 修订发布")));
        assertThat(configuration.rule(created.id()).state()).isEqualTo("RETIRED");
        assertThat(configuration.ruleRevisions(revised.id())).extracting(
                BusinessNumberingConfigurationService.RuleView::revisionNumber).containsExactly(2, 1);
        assertThat(configuration.rules("", "PROJECT", "")).singleElement()
                .satisfies(current -> assertThat(current.revisionNumber()).isEqualTo(2));
        assertThat(configuration.rules("", "PROJECT", "RETIRED")).hasSize(1);
        assertThat(configuration.ruleSummary()).satisfies(summary -> {
            assertThat(summary.published()).isGreaterThanOrEqualTo(1);
            assertThat(summary.history()).isGreaterThanOrEqualTo(1);
            assertThat(summary.series()).isGreaterThanOrEqualTo(1);
        });
        assertThat(generator.next("PROJECT", "GC")).isEqualTo("PX-20260026-R2");

        List<Callable<String>> configuredCalls = new ArrayList<>();
        for (int index = 0; index < 40; index++) configuredCalls.add(() -> generator.next("PROJECT", "GC"));
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<String> values = pool.invokeAll(configuredCalls).stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).toList();
            assertThat(new HashSet<>(values)).hasSize(40);
            assertThat(values).allMatch(value -> value.matches("PX-\\d{4}\\d{4}-R2"));
        }
        assertThat(sql.queryForObject("SELECT current_value FROM business_number_sequence WHERE rule_series_id=?",
                Long.class, created.seriesId())).isEqualTo(66L);

        var template = transaction.execute(status -> configuration.createTemplate(
                new BusinessNumberingConfigurationService.TemplateWrite("SHOWCASE-TMPL", "REPORT_DISTRIBUTION", 0)));
        assertThat(template).isNotNull();
        transaction.execute(status -> configuration.transitionTemplate(template.id(),
                new TransitionCommand("PUBLISH", template.version(), "CONTROLLED_RELEASE", "SHOWCASE 模板发布")));
        var publishedTemplate = configuration.template(template.id());
        var templateDraft = transaction.execute(status -> configuration.reviseTemplate(
                publishedTemplate.id(), publishedTemplate.version()));
        assertThat(templateDraft).isNotNull();
        var updatedTemplate = transaction.execute(status -> configuration.updateTemplate(templateDraft.id(),
                new BusinessNumberingConfigurationService.TemplateWrite("SHOWCASE-TMPL-R2", "REPORT_DISTRIBUTION",
                        templateDraft.version())));
        assertThat(updatedTemplate).isNotNull();
        transaction.execute(status -> configuration.transitionTemplate(updatedTemplate.id(),
                new TransitionCommand("PUBLISH", updatedTemplate.version(), "CONTROLLED_RELEASE", "SHOWCASE 模板修订发布")));
        assertThat(configuration.template(publishedTemplate.id()).state()).isEqualTo("RETIRED");
        assertThat(configuration.templateRevisions(updatedTemplate.id())).extracting(
                BusinessNumberingConfigurationService.TemplateView::revisionNumber).containsExactly(2, 1);
        assertThat(configuration.templates("", "REPORT_DISTRIBUTION", "")).singleElement()
                .satisfies(current -> assertThat(current.revisionNumber()).isEqualTo(2));
        assertThat(configuration.templates("", "REPORT_DISTRIBUTION", "RETIRED")).hasSize(1);
        assertThat(configuration.templateSummary().history()).isGreaterThanOrEqualTo(1);
        verify(audit, times(2)).record(eq("BUSINESS_NUMBERING_RULE_PUBLISH"),
                eq("BUSINESS_NUMBERING_RULE"), any(), any(), any(), any(), any());
        verify(audit, times(2)).record(eq("TEMPLATE_NUMBERING_RULE_PUBLISH"),
                eq("TEMPLATE_NUMBERING_RULE"), any(), any(), any(), any(), any());
    }

    @Test
    void auditQueryProvidesExactReferenceFacetsAndStableFilteredCursorPages() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID organization = sql.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = sql.queryForObject("SELECT id FROM department WHERE organization_id=? ORDER BY code LIMIT 1",
                UUID.class, organization);
        UUID actorId = UUID.randomUUID();
        String suffix = actorId.toString().substring(0, 8);
        String account = "audit-v50-" + suffix;
        sql.update("""
                INSERT INTO app_user(id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?)
                """, actorId, organization, department, account, "not-a-login-hash", "SHOWCASE 审计账号");
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, null, organization, department,
                account, "SHOWCASE 审计账号", false, 0, "ALL", List.of("SYSTEM_ADMIN"),
                List.of("MODULE_READ", "SYSTEM_ADMIN")));
        String marker = "SHOWCASE-AUDIT-" + suffix;
        OffsetDateTime firstAt = OffsetDateTime.parse("2026-08-21T10:00:00+08:00");
        OffsetDateTime secondAt = firstAt.plusMinutes(1);
        sql.update("""
                INSERT INTO audit_event(id,occurred_at,request_id,actor_id,actor_name,action,resource_type,
                  resource_id,organization_id,after_json,comment,remote_address)
                VALUES (?,?,?,?,?,'BUSINESS_NUMBERING_RULE_CREATED','BUSINESS_NUMBERING_RULE',?,?,CAST(? AS jsonb),?,?)
                """, UUID.randomUUID(), firstAt, "v50-audit-created", actorId, "SHOWCASE 审计账号",
                marker + "-1", organization, "{\"code\":\"" + marker + "\"}", "SHOWCASE 新增审计", "192.0.2.50");
        sql.update("""
                INSERT INTO audit_event(id,occurred_at,request_id,actor_id,actor_name,action,resource_type,
                  resource_id,organization_id,after_json,reason_code,comment,remote_address)
                VALUES (?,?,?,?,?,'BUSINESS_NUMBERING_SEQUENCE_RESET','BUSINESS_NUMBERING_RULE',?,?,CAST(? AS jsonb),?,?,?)
                """, UUID.randomUUID(), secondAt, "v50-audit-reset", actorId, "SHOWCASE 审计账号",
                marker + "-2", organization, "{\"code\":\"" + marker + "\"}", "MANUAL_SEQUENCE_RESET",
                "SHOWCASE 重置审计", "192.0.2.50");

        AuditQueryService query = new AuditQueryService(JdbcClient.create(dataSource), currentActor);
        assertThat(query.facets().businesses()).hasSize(83)
                .extracting(AuditQueryService.Option::label)
                .contains("配置", "委托单", "工程流水号", "模板编号配置", "电子门牌");
        assertThat(query.facets().operations()).extracting(AuditQueryService.Option::code)
                .containsExactly("LOGIN", "BROWSE", "CREATE", "UPDATE", "DELETE", "LOGOUT");
        assertThat(sql.queryForObject("SELECT audit_operation_type('BUSINESS_NUMBERING_RULE_RETIRE')", String.class))
                .isEqualTo("DELETE");
        assertThat(sql.queryForObject("SELECT audit_operation_type('TEMPLATE_NUMBERING_RULE_DISCARD')", String.class))
                .isEqualTo("DELETE");
        assertThat(sql.queryForObject("SELECT audit_operation_type('OPERATIONAL_VIEW_EXPORT')", String.class))
                .isEqualTo("BROWSE");
        assertThat(sql.queryForObject("SELECT audit_operation_type('REPORT_CREATED')", String.class))
                .isEqualTo("CREATE");

        var firstPage = query.list(new AuditQueryService.Filters(account, firstAt.minusMinutes(1),
                secondAt.plusMinutes(1), marker, "SRC_030", "", "BUSINESS_NUMBERING_RULE", "", 1));
        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(firstPage.items().getFirst().actorAccount()).isEqualTo(account);
        assertThat(firstPage.items().getFirst().remoteAddress()).isEqualTo("192.0.2.50");
        assertThat(firstPage.items().getFirst().businessLabel()).isEqualTo("工程流水号");
        var secondPage = query.list(new AuditQueryService.Filters(account, firstAt.minusMinutes(1),
                secondAt.plusMinutes(1), marker, "SRC_030", "", "BUSINESS_NUMBERING_RULE",
                firstPage.nextCursor(), 1));
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().getFirst().id()).isNotEqualTo(firstPage.items().getFirst().id());
        assertThat(secondPage.hasMore()).isFalse();

        var createdOnly = query.list(new AuditQueryService.Filters(account, firstAt.minusMinutes(1),
                secondAt.plusMinutes(1), marker, "SRC_030", "CREATE", "BUSINESS_NUMBERING_RULE", "", 100));
        assertThat(createdOnly.items()).singleElement().satisfies(event -> {
            assertThat(event.operationType()).isEqualTo("CREATE");
            assertThat(event.action()).isEqualTo("BUSINESS_NUMBERING_RULE_CREATED");
            assertThat(event.operationData()).isEqualTo(marker);
        });
        assertThatThrownBy(() -> query.list(new AuditQueryService.Filters(account, secondAt, firstAt,
                marker, "SRC_030", "", "", "", 10)))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("AUDIT_DATE_RANGE_INVALID"));
    }

    @Test
    void equipmentWorkflowTablesEnforceSingleOpenFlowAndStateCompleteness() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject("SELECT id FROM department WHERE organization_id=? LIMIT 1", UUID.class, organization);
        UUID person = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                person, department, "EQUIPMENT-" + person.toString().substring(0, 8), "设备约束测试人员");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?,?)
                """, actor, person, organization, department, "equipment-" + actor,
                "not-a-login-hash", "设备约束测试账号");
        UUID equipment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipment(id,organization_id,code,name,model,state,approval_state,inventory_status)
                VALUES (?,?,?,?,?,'BORROWED','APPROVED','OUT')
                """, equipment, organization, "CONSTRAINT-EQ-" + equipment.toString().substring(0, 8),
                "设备流程约束测试", "MODEL-C");
        OffsetDateTime borrowedAt = OffsetDateTime.now().minusHours(1);
        UUID firstBorrow = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipment_borrow(id,equipment_id,borrower_id,borrowed_at,borrowed_period,
                  planned_return_at,planned_return_period,before_condition,state,created_by)
                VALUES (?,?,?,?,'AM',?,'PM','NORMAL','BORROWED',?)
                """, firstBorrow, equipment, person, borrowedAt, borrowedAt.plusDays(1), actor);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO equipment_borrow(id,equipment_id,borrower_id,borrowed_at,borrowed_period,
                  planned_return_at,planned_return_period,before_condition,state,created_by)
                VALUES (?,?,?,?,'AM',?,'PM','NORMAL','BORROWED',?)
                """, UUID.randomUUID(), equipment, person, borrowedAt, borrowedAt.plusDays(2), actor))
                .hasMessageContaining("equipment_one_open_borrow_ux");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE equipment_borrow SET state='RETURNED' WHERE id=?
                """, firstBorrow)).hasMessageContaining("violates check constraint");
    }

    @Test
    void businessDirectoryEnforcesTenantKeysVersionsAndProjectPlanBindings() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject("SELECT id FROM department WHERE organization_id=? LIMIT 1", UUID.class, organization);
        UUID actor = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?)
                """, actor, organization, department, "directory-" + actor, "not-a-login-hash", "业务目录约束账号");
        UUID customer = UUID.randomUUID();
        String customerCode = "CUSTOMER-" + customer.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO customer(id,organization_id,code,name,short_name,unit_type,commissioning_unit)
                VALUES (?,?,?,?,?,'ENTRUSTING',true)
                """, customer, organization, customerCode, "约束测试客户", "约束客户");
        jdbc.update("""
                INSERT INTO customer_settlement_contact(id,customer_id,name,phone,primary_contact)
                VALUES (?,?,?,'13800000000',true)
                """, UUID.randomUUID(), customer, "结算人一");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customer_settlement_contact(id,customer_id,name,phone,primary_contact)
                VALUES (?,?,?,'13900000000',true)
                """, UUID.randomUUID(), customer, "结算人二"))
                .hasMessageContaining("customer_one_primary_settlement_ux");

        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,organization_id,customer_id,number,name,status,created_by,
                  owner_name,supervision_name,construction_name)
                VALUES (?,?,?,?,?,'DRAFT',?,?,?,?)
                """, project, organization, customer, "PROJECT-" + project.toString().substring(0, 8),
                "约束测试工程", actor, "建设单位", "监理单位", "施工单位");
        jdbc.update("""
                INSERT INTO project_party(id,project_id,party_role,customer_id,name_snapshot,manual_entry)
                VALUES (?,?,'ENTRUSTING',?,?,false)
                """, UUID.randomUUID(), project, customer, "约束测试客户");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO project_party(id,project_id,party_role,customer_id,name_snapshot,manual_entry)
                VALUES (?,?,'ENTRUSTING',?,?,false)
                """, UUID.randomUUID(), project, customer, "重复委托单位"))
                .hasMessageContaining("project_party_project_id_party_role_key");
        UUID parameter = jdbc.queryForObject("SELECT id FROM test_parameter ORDER BY created_at,id LIMIT 1", UUID.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO project_detection_plan(id,project_id,parameter_id,project_name,parameter_name,
                  planned_quantity,unit,created_by)
                VALUES (?,?,?,?,?,1,'组',?)
                """, UUID.randomUUID(), project, parameter, "试验项目", "试验参数", actor))
                .hasMessageContaining("violates check constraint");
        assertThatThrownBy(() -> jdbc.update("UPDATE project SET progress_percent=101 WHERE id=?", project))
                .hasMessageContaining("violates check constraint");
    }

    @Test
    void testRecordConfirmationRequiresAnIndependentReviewer() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject("SELECT id FROM department WHERE organization_id=? LIMIT 1", UUID.class, organization);
        UUID person = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String suffix = actorId.toString().substring(0, 8);
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                person, department, "TEST-REVIEW-" + suffix, "试验录入人");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?,?)
                """, actorId, person, organization, department, "test-review-" + suffix,
                "not-a-login-hash", "试验录入人");
        UUID customer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer(id,organization_id,code,name,short_name,unit_type,commissioning_unit)
                VALUES (?,?,?,?,?,'ENTRUSTING',true)
                """, customer, organization, "TEST-REVIEW-" + suffix, "独立复核客户", "复核客户");
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,organization_id,customer_id,number,name,status,created_by)
                VALUES (?,?,?,?,?,'ACTIVE',?)
                """, project, organization, customer, "TEST-REVIEW-" + suffix, "独立复核工程", actorId);
        UUID commission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission(id,organization_id,project_id,customer_id,number,contact_name,
                  commissioned_on,state,created_by,received_department_id)
                VALUES (?,?,?,?,?,? ,current_date,'TESTING',?,?)
                """, commission, organization, project, customer, "TEST-REVIEW-" + suffix,
                "复核联系人", actorId, department);
        UUID sample = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sample(id,commission_id,number,name,received_quantity,available_quantity,unit,state)
                VALUES (?,?,?,?,1,1,'组','IN_TEST')
                """, sample, commission, "TEST-REVIEW-" + suffix, "独立复核样品");
        UUID parameter = jdbc.queryForObject("SELECT id FROM test_parameter ORDER BY created_at,id LIMIT 1", UUID.class);
        UUID task = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_task(id,commission_id,sample_id,parameter_id,number,state,assigned_to)
                VALUES (?,?,?,?,?,'SUBMITTED',?)
                """, task, commission, sample, parameter, "TEST-REVIEW-" + suffix, person);
        UUID record = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_record(id,task_id,revision,values_json,result,state,recorded_by,submitted_at)
                VALUES (?,?,0,'{}'::jsonb,'PENDING','SUBMITTED',?,now())
                """, record, task, person);

        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, person, organization,
                department, "test-review-" + suffix, "试验录入人", false, 0,
                "SELF", List.of("TESTER"), List.of("MODULE_READ", "BUSINESS_TRANSITION")));
        TestRecordService service = new TestRecordService(JdbcClient.create(dataSource), currentActor,
                new ObjectMapper(), mock(FormulaEvaluator.class), mock(NumberingService.class),
                mock(AuditService.class), mock(ExceptionApprovalService.class), mock(DataScopeService.class),
                mock(EquipmentOperationService.class));

        assertThatThrownBy(() -> service.transition(record,
                new TestRecordService.Transition("CONFIRM", 0, "REVIEW", "试图自行复核", "CONFORMING")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("必须由录入人员以外的复核人员确认");
        assertThat(jdbc.queryForObject("SELECT state FROM test_record WHERE id=?", String.class, record))
                .isEqualTo("SUBMITTED");
    }

    @Test
    void submittedTestCreatesOneCompletedEquipmentUsageHistory() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject("SELECT id FROM department WHERE organization_id=? LIMIT 1", UUID.class, organization);
        UUID person = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String suffix = actorId.toString().substring(0, 8);
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                person, department, "TEST-USAGE-" + suffix, "设备使用人");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?,?)
                """, actorId, person, organization, department, "test-usage-" + suffix,
                "not-a-login-hash", "设备使用人");
        UUID customer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer(id,organization_id,code,name,short_name,unit_type,commissioning_unit)
                VALUES (?,?,?,?,?,'ENTRUSTING',true)
                """, customer, organization, "TEST-USAGE-" + suffix, "设备履历客户", "履历客户");
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,organization_id,customer_id,number,name,status,created_by)
                VALUES (?,?,?,?,?,'ACTIVE',?)
                """, project, organization, customer, "TEST-USAGE-" + suffix, "设备履历工程", actorId);
        UUID commission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission(id,organization_id,project_id,customer_id,number,contact_name,
                  commissioned_on,state,created_by,received_department_id)
                VALUES (?,?,?,?,?,?,current_date,'TESTING',?,?)
                """, commission, organization, project, customer, "TEST-USAGE-" + suffix,
                "履历联系人", actorId, department);
        UUID sample = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sample(id,commission_id,number,name,received_quantity,available_quantity,unit,state)
                VALUES (?,?,?,?,1,1,'组','IN_TEST')
                """, sample, commission, "TEST-USAGE-" + suffix, "设备履历样品");
        UUID parameter = jdbc.queryForObject("SELECT id FROM test_parameter ORDER BY created_at,id LIMIT 1", UUID.class);
        UUID task = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_task(id,commission_id,sample_id,parameter_id,number,state,assigned_to,started_at)
                VALUES (?,?,?,?,?,'IN_PROGRESS',?,now()-interval '1 minute')
                """, task, commission, sample, parameter, "TEST-USAGE-" + suffix, person);
        UUID equipment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipment(id,organization_id,code,name,model,location,state,approval_state)
                VALUES (?,?,?,?,?,?,'AVAILABLE','APPROVED')
                """, equipment, organization, "TEST-USAGE-" + suffix, "压力试验机", "YS-1000", "力学试验室");

        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, person, organization,
                department, "test-usage-" + suffix, "设备使用人", false, 0,
                "SELF", List.of("TESTER"), List.of("MODULE_READ", "BUSINESS_TRANSITION")));
        AuditService audit = mock(AuditService.class);
        EquipmentOperationService operations = new EquipmentOperationService(JdbcClient.create(dataSource),
                currentActor, audit, mock(StoredFileService.class), mock(com.lanchuan.lims.equipment.EquipmentService.class));

        operations.recordCompletedTestUsage(task, equipment, person);
        operations.recordCompletedTestUsage(task, equipment, person);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_usage_record WHERE task_id=?", Integer.class, task))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM equipment_usage_record WHERE task_id=?", String.class, task))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT sample_name FROM equipment_usage_record WHERE task_id=?", String.class, task))
                .isEqualTo("设备履历样品");
        verify(audit, times(1)).record(eq("EQUIPMENT_USAGE_CREATED_FROM_TEST"), eq("EQUIPMENT_USAGE"),
                any(UUID.class), eq(null), any(), eq(null), eq("试验记录提交"));
    }

    @Test
    void correctiveActionExecutionRequiresItsAssignedOwner() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject("SELECT id FROM department WHERE organization_id=? LIMIT 1", UUID.class, organization);
        UUID owner = UUID.randomUUID();
        UUID ownerActorId = UUID.randomUUID();
        UUID actorPerson = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String suffix = actorId.toString().substring(0, 8);
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                owner, department, "CAPA-OWNER-" + suffix, "处置责任人");
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                actorPerson, department, "CAPA-ACTOR-" + suffix, "非责任操作人");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?,?)
                """, ownerActorId, owner, organization, department, "capa-owner-" + suffix,
                "not-a-login-hash", "处置责任人");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?,?)
                """, actorId, actorPerson, organization, department, "capa-actor-" + suffix,
                "not-a-login-hash", "非责任操作人");
        UUID customer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer(id,organization_id,code,name,short_name,unit_type,commissioning_unit)
                VALUES (?,?,?,?,?,'ENTRUSTING',true)
                """, customer, organization, "CAPA-" + suffix, "CAPA 责任分离客户", "CAPA 客户");
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,organization_id,customer_id,number,name,status,created_by)
                VALUES (?,?,?,?,?,'ACTIVE',?)
                """, project, organization, customer, "CAPA-" + suffix, "CAPA 责任分离工程", actorId);
        UUID commission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission(id,organization_id,project_id,customer_id,number,contact_name,
                  commissioned_on,state,created_by,received_department_id)
                VALUES (?,?,?,?,?,? ,current_date,'TESTING',?,?)
                """, commission, organization, project, customer, "CAPA-" + suffix,
                "CAPA 联系人", actorId, department);
        UUID nonconformance = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO nonconformance(id,number,source_type,source_id,commission_id,severity,description,state,opened_by)
                VALUES (?,?, 'TEST_RECORD', ?, ?, 'HIGH', '试验结果为NONCONFORMING：受控回归',
                  'DISPOSITION_IN_PROGRESS', ?)
                """, nonconformance, "NCR-CAPA-" + suffix, UUID.randomUUID(), commission, actorId);
        UUID action = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corrective_action(id,nonconformance_id,action_type,description,owner_id,due_on,state)
                VALUES (?,?,'CORRECTIVE','仅责任人执行的纠正措施',?,current_date+1,'PLANNED')
                """, action, nonconformance, owner);

        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, actorPerson, organization,
                department, "capa-actor-" + suffix, "非责任操作人", false, 0,
                "ORGANIZATION", List.of("QUALITY_MANAGER"), List.of("MODULE_READ", "BUSINESS_TRANSITION")));
        DataScopeService scope = mock(DataScopeService.class);
        when(scope.canAccessCommission(commission)).thenReturn(true);
        NonconformanceService service = new NonconformanceService(JdbcClient.create(dataSource), currentActor,
                mock(NumberingService.class), mock(AuditService.class), new ObjectMapper(), scope);

        assertThat(service.require(nonconformance).description()).doesNotContain("NONCONFORMING");
        assertThatThrownBy(() -> service.transitionAction(action,
                new NonconformanceService.ActionTransition("START", "越权执行", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("只有措施责任人");
        assertThat(jdbc.queryForObject("SELECT state FROM corrective_action WHERE id=?", String.class, action))
                .isEqualTo("PLANNED");

        CurrentActor ownerActor = mock(CurrentActor.class);
        when(ownerActor.require()).thenReturn(new AuthenticatedUser(ownerActorId, owner, organization,
                department, "capa-owner-" + suffix, "处置责任人", false, 0,
                "SELF", List.of("TESTER"), List.of("MODULE_READ", "BUSINESS_TRANSITION")));
        NonconformanceService ownerService = new NonconformanceService(JdbcClient.create(dataSource), ownerActor,
                mock(NumberingService.class), mock(AuditService.class), new ObjectMapper(), scope);
        assertThat(ownerService.transitionAction(action,
                new NonconformanceService.ActionTransition("START", "开始处置", null)).state())
                .isEqualTo("IN_PROGRESS");
        assertThat(ownerService.transitionAction(action,
                new NonconformanceService.ActionTransition("COMPLETE", "提交处置证据", List.of("复测记录-001"))).state())
                .isEqualTo("COMPLETED");
    }

    @Test
    void precommissionAndChangeRequestsEnforceIndependentControlledReview() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject("SELECT id FROM department WHERE organization_id=? LIMIT 1", UUID.class, organization);
        UUID applicant = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?),(?,?,?,?,?,?)
                """, applicant, organization, department, "change-applicant-" + applicant, "not-a-login-hash", "变更申请人",
                reviewer, organization, department, "change-reviewer-" + reviewer, "not-a-login-hash", "变更审核人");
        UUID customer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer(id,organization_id,code,name,short_name,unit_type,commissioning_unit)
                VALUES (?,?,?,?,?,'ENTRUSTING',true)
                """, customer, organization, "CHANGE-" + customer.toString().substring(0, 8), "变更约束客户", "变更客户");
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,organization_id,customer_id,number,name,status,created_by)
                VALUES (?,?,?,?,?,'ACTIVE',?)
                """, project, organization, customer, "CHANGE-" + project.toString().substring(0, 8), "变更约束工程", applicant);
        UUID commission = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission(id,organization_id,project_id,customer_id,number,precommission_number,
                  contact_name,commissioned_on,due_on,state,created_by,submitted_at)
                VALUES (?,?,?,?,?,?,?,current_date,current_date+15,'ACCEPTED',?,now())
                """, commission, organization, project, customer, "WT-CONSTRAINT-" + commission.toString().substring(0, 8),
                "YWT-CONSTRAINT-" + commission.toString().substring(0, 8), "原联系人", applicant);
        UUID request = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commission_change_request(id,commission_id,request_type,processing_method,reason,
                  requested_contact_name,commission_version_snapshot,requested_by)
                VALUES (?,?,'CONTACT','UPDATE_CURRENT','变更联系人','新联系人',0,?)
                """, request, commission, applicant);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO commission_change_request(id,commission_id,request_type,processing_method,reason,
                  requested_due_on,commission_version_snapshot,requested_by)
                VALUES (?,?,'DUE_DATE','UPDATE_CURRENT','重复未完申请',current_date+20,0,?)
                """, UUID.randomUUID(), commission, applicant)).hasMessageContaining("commission_one_open_change_request_ux");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE commission_change_request SET state='APPROVED',reviewed_by=?,reviewed_at=now(),
                  applied_commission_version=1,result_commission_id=? WHERE id=?
                """, applicant, commission, request)).hasMessageContaining("violates check constraint");
        assertThat(jdbc.update("""
                UPDATE commission_change_request SET state='APPROVED',reviewed_by=?,reviewed_at=now(),
                  applied_commission_version=1,result_commission_id=? WHERE id=?
                """, reviewer, commission, request)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE commission_change_request SET state='REJECTED',applied_commission_version=1 WHERE id=?
                """, request)).hasMessageContaining("violates check constraint");
    }

    @Test
    void contractFinanceEnforcesTenantKeysSettlementAndMakerChecker() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject("SELECT id FROM department WHERE organization_id=? LIMIT 1", UUID.class, organization);
        UUID maker = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?),(?,?,?,?,?,?)
                """, maker, organization, department, "finance-maker-" + maker, "not-a-login-hash", "财务申请人",
                approver, organization, department, "finance-approver-" + approver, "not-a-login-hash", "财务复核人");
        UUID customer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer(id,organization_id,code,name,short_name,unit_type,commissioning_unit)
                VALUES (?,?,?,?,?,'ENTRUSTING',true)
                """, customer, organization, "FIN-" + customer.toString().substring(0, 8), "财务约束客户", "财务客户");
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,organization_id,customer_id,number,name,status,created_by)
                VALUES (?,?,?,?,?,'ACTIVE',?)
                """, project, organization, customer, "FIN-" + project.toString().substring(0, 8), "财务约束工程", maker);
        UUID template = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO contract_price_template(id,organization_id,code,name,pricing_method,currency,
                  state,published_by,published_at,created_by)
                VALUES (?,?,?,'约束计价模板','UNIT_PRICE','CNY','PUBLISHED',?,now(),?)
                """, template, organization, "TPL-" + template.toString().substring(0, 8), approver, maker);
        UUID contract = UUID.randomUUID();
        String contractNumber = "HT-" + contract.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO business_contract(id,organization_id,number,name,contract_type,customer_id,project_id,
                  counterparty_name,signed_on,price_template_id,signed_amount,contract_price,owner_id,state,created_by)
                VALUES (?,?,?,?, 'TESTING_SERVICE',?,?,?,current_date,?,100,100,?,'ACTIVE',?)
                """, contract, organization, contractNumber, "财务约束合同", customer, project,
                "财务约束客户", template, maker, maker);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO business_contract(id,organization_id,number,name,contract_type,customer_id,project_id,
                  counterparty_name,owner_id,created_by)
                VALUES (?,?,?,'重复合同','OTHER',?,?,?, ?,?)
                """, UUID.randomUUID(), organization, contractNumber, customer, project, "财务约束客户", maker, maker))
                .hasMessageContaining("business_contract_organization_id_number_key");

        UUID fee = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fee_statement(id,organization_id,contract_id,project_id,item_name,quantity,unit,
                  unit_price,amount,state,lock_reason,locked_by,locked_at,created_by)
                VALUES (?,?,?,?,'约束费用',1,'项',100,100,'LOCKED','结算锁定',?,now(),?)
                """, fee, organization, contract, project, approver, maker);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE fee_statement SET state='SETTLED',paid_amount=50 WHERE id=?
                """, fee)).hasMessageContaining("violates check constraint");

        UUID payment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO finance_payment(id,organization_id,number,contract_id,payable_amount,planned_on,
                  payee_name,purpose,state,created_by)
                VALUES (?,?,?,?,100,current_date,'供应单位','检测服务','SUBMITTED',?)
                """, payment, organization, "FK-" + payment.toString().substring(0, 8), contract, maker);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE finance_payment SET state='APPROVED',approved_by=?,approved_at=now() WHERE id=?
                """, maker, payment)).hasMessageContaining("violates check constraint");
        assertThat(jdbc.update("""
                UPDATE finance_payment SET state='APPROVED',approved_by=?,approved_at=now() WHERE id=?
                """, approver, payment)).isEqualTo(1);
    }

    @Test
    void controlledSigningEnforcesOneActiveSchemeAndPositionBounds() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID organization = jdbc.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID department = jdbc.queryForObject("SELECT id FROM department WHERE organization_id=? LIMIT 1", UUID.class, organization);
        UUID person = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                person, department, "SIGNING-" + person.toString().substring(0, 8), "签章约束测试人员");
        jdbc.update("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?,?)
                """, actor, person, organization, department, "signing-" + actor, "not-a-login-hash", "签章约束测试账号");
        UUID signature = signingAsset(jdbc, organization, actor, person, "SIGNATURE", "测试人员签名");
        UUID seal = signingAsset(jdbc, organization, actor, null, "SEAL", "测试机构章");
        UUID firstScheme = signatureScheme(jdbc, organization, actor, "ACTIVE", "约束方案一");
        jdbc.update("""
                INSERT INTO signature_scheme_position(id,scheme_id,position_type,signing_asset_id,label,page_rule,
                  x_mm,y_mm,width_mm,height_mm,sort_order) VALUES (?,?,?,?,?,'LAST',100,210,45,22,0)
                """, UUID.randomUUID(), firstScheme, "SIGNATURE", signature, "签发人");
        jdbc.update("""
                INSERT INTO signature_scheme_position(id,scheme_id,position_type,signing_asset_id,label,page_rule,
                  x_mm,y_mm,width_mm,height_mm,sort_order) VALUES (?,?,?,?,?,'LAST',140,205,30,30,0)
                """, UUID.randomUUID(), firstScheme, "SEAL", seal, "机构章");

        assertThatThrownBy(() -> signatureScheme(jdbc, organization, actor, "ACTIVE", "约束方案二"))
                .hasMessageContaining("signature_scheme_one_active_ix");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO signature_scheme_position(id,scheme_id,position_type,signing_asset_id,label,page_rule,
                  x_mm,y_mm,width_mm,height_mm,sort_order) VALUES (?,?,?,?,?,'LAST',181,0,20,20,1)
                """, UUID.randomUUID(), firstScheme, "SEAL", seal, "越界机构章"))
                .hasMessageContaining("signature_scheme_position_x_mm_check");

        UUID replacement = signingAsset(jdbc, organization, actor, person, "SIGNATURE", "待切换人员签名", false);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actor, person, organization, department,
                "signing-contract", "签章约束测试账号", false, 0, "ALL",
                List.of("SYSTEM_ADMIN"), List.of("SIGNING_ASSET_MANAGE")));
        RecentPasswordService recentPassword = mock(RecentPasswordService.class);
        when(recentPassword.isVerified()).thenReturn(true);
        SigningAssetService assets = new SigningAssetService(JdbcClient.create(dataSource), currentActor,
                mock(AuditService.class), Base64.getEncoder().encodeToString(new byte[32]), true, recentPassword);
        assertThatThrownBy(() -> assets.transition(replacement,
                new SigningAssetService.Transition("ACTIVATE", 0, "CONTROLLED_SIGNING", "切换签名")))
                .isInstanceOf(ApiException.class).hasMessageContaining("已绑定启用中的签章方案");
    }

    @Test
    void generatedFilesPreserveUnicodeBasenamesWithoutDependingOnTheHostCharset() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> identity = jdbc.queryForMap("""
                SELECT id,person_id,organization_id,department_id,username,display_name
                FROM app_user ORDER BY created_at,id LIMIT 1
                """);
        UUID actorId = (UUID) identity.get("id");
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId,
                (UUID) identity.get("person_id"), (UUID) identity.get("organization_id"),
                (UUID) identity.get("department_id"), (String) identity.get("username"),
                (String) identity.get("display_name"), false, 0, "ALL",
                List.of("SYSTEM_ADMIN"), List.of("FILE_DOWNLOAD")));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        StoredFileService service = new StoredFileService(JdbcClient.create(dataSource), currentActor,
                mock(AuditService.class), java.nio.file.Path.of("/tmp/lims-unicode-file-" + suffix), 1024 * 1024);
        StoredFileService.View stored = service.storeGenerated(
                "%PDF-1.4\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "受控导出\\样品台账-2026-08-23.pdf", "application/pdf", "QUALITY_EXPORT", actorId);
        assertThat(stored.originalName()).isEqualTo("样品台账-2026-08-23.pdf");
    }

    @Test
    void departmentConfigurationOwnsHierarchyProjectScopeAndControlledRetirement() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID organization = sql.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID actorDepartment = sql.queryForObject(
                "SELECT id FROM department WHERE organization_id=? ORDER BY code LIMIT 1", UUID.class, organization);
        UUID projectNode = sql.queryForObject("""
                SELECT revision.project_node_id
                FROM detection_project_revision revision
                JOIN detection_catalog_node node ON node.id=revision.project_node_id
                WHERE revision.status='PUBLISHED' AND node.status='ACTIVE'
                ORDER BY node.id LIMIT 1
                """, UUID.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), null, organization,
                actorDepartment, "department-contract", "部门约束测试", false, 0,
                "ALL", List.of("SYSTEM_ADMIN"), List.of("MODULE_READ", "MODULE_WRITE")));
        AuditService audit = mock(AuditService.class);
        DepartmentService service = new DepartmentService(JdbcClient.create(dataSource), currentActor, audit);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        DepartmentService.View parent = service.create(new DepartmentService.Create(
                "SHOWCASE-PARENT-" + suffix, "SHOWCASE 部门约束父级 " + suffix,
                null, 10, null, null, List.of(projectNode)));
        DepartmentService.View child = service.create(new DepartmentService.Create(
                "SHOWCASE-CHILD-" + suffix, "SHOWCASE 部门约束子级 " + suffix,
                parent.id(), 20, null, null, List.of(projectNode)));

        assertThat(service.require(child.id()).path()).contains(parent.name()).endsWith(child.name());
        assertThat(service.require(parent.id()).detectionProjectNodeIds()).containsExactly(projectNode);
        assertThatThrownBy(() -> service.update(parent.id(), new DepartmentService.Update(
                parent.name(), child.id(), 10, null, null, List.of(projectNode), parent.version())))
                .isInstanceOf(ApiException.class).hasMessageContaining("不能形成循环");

        DepartmentService.View updated = service.update(parent.id(), new DepartmentService.Update(
                parent.name() + " 已核对", null, 9, null, null, List.of(projectNode), parent.version()));
        assertThatThrownBy(() -> service.update(parent.id(), new DepartmentService.Update(
                updated.name(), null, 8, null, null, List.of(projectNode), parent.version())))
                .isInstanceOf(ApiException.class).hasMessageContaining("其他用户更新");
        assertThatThrownBy(() -> service.transition(parent.id(),
                new TransitionCommand("RETIRE", updated.version(), "TEST", "测试停用")))
                .isInstanceOf(ApiException.class).hasMessageContaining("仍有在用关联");

        service.transition(child.id(), new TransitionCommand("RETIRE", child.version(), "TEST", "测试停用"));
        DepartmentService.View retired = service.require(child.id());
        assertThat(retired.active()).isFalse();
        assertThat(retired.retiredReason()).isEqualTo("测试停用");
        assertThatThrownBy(() -> service.update(child.id(), new DepartmentService.Update(
                retired.name(), retired.parentId(), retired.sortOrder(), null, null,
                retired.detectionProjectNodeIds(), retired.version())))
                .isInstanceOf(ApiException.class).hasMessageContaining("不允许动作 UPDATE");
        service.transition(child.id(), new TransitionCommand("RESTORE", retired.version(), "TEST", "测试恢复"));
        assertThat(service.require(child.id()).active()).isTrue();
        verify(audit).record(eq("DEPARTMENT_RETIRE"), eq("DEPARTMENT"), eq(child.id()),
                any(), any(), eq("TEST"), eq("测试停用"));
    }

    @Test
    void taskAssignmentRequiresTheAssigneesDepartmentToOwnTheDetectionProject() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID organization = sql.queryForObject("SELECT id FROM organization ORDER BY code LIMIT 1", UUID.class);
        UUID actorDepartment = sql.queryForObject(
                "SELECT id FROM department WHERE organization_id=? ORDER BY code LIMIT 1", UUID.class, organization);
        Map<String, Object> parameter = sql.queryForMap("""
                SELECT parameter.id,revision.project_node_id
                FROM test_parameter parameter
                JOIN detection_project_revision revision ON revision.id=parameter.project_revision_id
                WHERE parameter.status='PUBLISHED'
                  AND parameter.definition_json->>'qualificationCode' IS NULL
                ORDER BY parameter.id LIMIT 1
                """);
        UUID parameterId = (UUID) parameter.get("id");
        UUID projectNodeId = (UUID) parameter.get("project_node_id");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID restrictedDepartment = UUID.randomUUID();
        sql.update("""
                INSERT INTO department(id,organization_id,code,name,sort_order)
                VALUES (?,?,?,?,50)
                """, restrictedDepartment, organization, "TASK-SCOPE-" + suffix,
                "SHOWCASE 任务范围部门 " + suffix);
        UUID tester = UUID.randomUUID();
        sql.update("INSERT INTO person(id,department_id,employee_number,name) VALUES (?,?,?,?)",
                tester, restrictedDepartment, "TASK-TESTER-" + suffix, "任务范围检测员");
        UUID actorId = UUID.randomUUID();
        sql.update("""
                INSERT INTO app_user(id,organization_id,department_id,username,password_hash,display_name)
                VALUES (?,?,?,?,?,?)
                """, actorId, organization, actorDepartment, "task-scope-" + suffix,
                "not-a-login-hash", "任务范围分配账号");
        UUID customer = UUID.randomUUID();
        sql.update("""
                INSERT INTO customer(id,organization_id,code,name,short_name,unit_type,commissioning_unit)
                VALUES (?,?,?,?,?,'ENTRUSTING',true)
                """, customer, organization, "TASK-CUSTOMER-" + suffix, "任务范围客户", "范围客户");
        UUID project = UUID.randomUUID();
        sql.update("""
                INSERT INTO project(id,organization_id,customer_id,number,name,status,created_by)
                VALUES (?,?,?,?,?,'ACTIVE',?)
                """, project, organization, customer, "TASK-PROJECT-" + suffix, "任务范围工程", actorId);
        UUID commission = UUID.randomUUID();
        sql.update("""
                INSERT INTO commission(id,organization_id,project_id,customer_id,number,contact_name,
                  commissioned_on,state,created_by,received_department_id)
                VALUES (?,?,?,?,?,? ,current_date,'ASSIGNED',?,?)
                """, commission, organization, project, customer, "TASK-COMMISSION-" + suffix,
                "任务范围联系人", actorId, actorDepartment);
        UUID sample = UUID.randomUUID();
        sql.update("""
                INSERT INTO sample(id,commission_id,number,name,received_quantity,available_quantity,unit,state)
                VALUES (?,?,?,?,1,1,'组','HANDED_OVER')
                """, sample, commission, "TASK-SAMPLE-" + suffix, "任务范围样品");
        UUID task = UUID.randomUUID();
        sql.update("""
                INSERT INTO test_task(id,commission_id,sample_id,parameter_id,number,state)
                VALUES (?,?,?,?,?,'UNASSIGNED')
                """, task, commission, sample, parameterId, "TASK-NUMBER-" + suffix);

        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new AuthenticatedUser(actorId, null, organization,
                actorDepartment, "task-scope-" + suffix, "任务范围分配账号", false, 0,
                "ALL", List.of("SYSTEM_ADMIN"), List.of("MODULE_READ", "BUSINESS_TRANSITION")));
        DataScopeService access = mock(DataScopeService.class);
        AuditService audit = mock(AuditService.class);
        TaskService service = new TaskService(JdbcClient.create(dataSource), currentActor,
                mock(NumberingService.class), audit, mock(ExceptionApprovalService.class),
                access, mock(SystemExperienceService.class),
                new DocumentControlSnapshotService(JdbcClient.create(dataSource)));

        assertThatThrownBy(() -> service.transition(task,
                new TaskService.Transition("ASSIGN", 0, "TEST", "范围拒绝", tester)))
                .isInstanceOf(ApiException.class).hasMessageContaining("所属部门未关联当前检测项目");
        verify(audit).recordRejected(eq("TASK_DEPARTMENT_PROJECT_SCOPE_REJECTED"), eq("TEST_TASK"),
                eq(task), any(), eq("ASSIGNEE_DEPARTMENT_PROJECT_NOT_ALLOWED"),
                eq("检测人员所属部门未关联当前检测项目"));
        sql.update("INSERT INTO department_detection_project(department_id,project_node_id) VALUES (?,?)",
                restrictedDepartment, projectNodeId);
        assertThat(service.transition(task,
                new TaskService.Transition("ASSIGN", 0, "TEST", "范围允许", tester)).state())
                .isEqualTo("ASSIGNED");
        assertThat(sql.queryForObject("SELECT assigned_to FROM test_task WHERE id=?", UUID.class, task))
                .isEqualTo(tester);
    }

    private UUID signingAsset(JdbcTemplate jdbc, UUID organization, UUID actor, UUID person,
                              String type, String name) {
        return signingAsset(jdbc, organization, actor, person, type, name, true);
    }

    private UUID signingAsset(JdbcTemplate jdbc, UUID organization, UUID actor, UUID person,
                              String type, String name, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO signing_asset(id,asset_type,organization_id,owner_id,owner_person_id,name,
                  engineering_type,seal_type,valid_from,valid_until,media_type,content_sha256,
                  encrypted_content,nonce,active,verification_state,pixel_width,pixel_height,created_by)
                VALUES (?,?,?,?,?,?,?,?,current_date-1,current_date+30,'image/png',?,decode('00','hex'),
                  decode('000000000000000000000000','hex'),?,'VERIFIED',250,130,?)
                """, id, type, organization, type.equals("SIGNATURE") ? actor : null, person, name,
                type.equals("SEAL") ? "水利工程" : null, type.equals("SEAL") ? "检测专用章" : null,
                "0".repeat(64), active, actor);
        return id;
    }

    private UUID signatureScheme(JdbcTemplate jdbc, UUID organization, UUID actor, String state, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO signature_scheme(id,organization_id,series_id,revision_number,name,state,created_by,updated_by)
                VALUES (?,?,?,1,?,?,?,?)
                """, id, organization, id, name, state, actor, actor);
        return id;
    }
}

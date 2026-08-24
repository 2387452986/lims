# Module Development Summary

## Module Contract
- Module ID: `lims`
- Root: repository root
- Responsibility: Provide a runnable, sanitized portfolio edition of the trial inspection information management system.
- Non-responsibilities: Production credentials/data, internal brand assets, source-system audit evidence, external hardware/platform implementations, or production certification.
- Public entry/API: Same-origin Vue application and REST JSON API under `/api`; OpenAPI is the machine-readable contract.
- Owned state/data: PostgreSQL owns business/audit state; a dedicated SHA-256 file volume owns immutable binary content.
- Dependencies and consumers: Vue is the in-repository API consumer; PostgreSQL, the file volume and isolated Chromium are external boundaries.
- Version source/current version: root `pom.xml`; `0.1.0-SNAPSHOT`.
- Compatibility policy: Fresh public history and disposable portfolio databases; internal production history is not a consumer of this repository.

## Confirmed Decisions
| ID | Decision | User or repository source | Consequence |
| --- | --- | --- | --- |
| D-001 | Use fictional organization, people, projects, data and a newly created logo in the public repository. | User request for complete anonymization | Public checks must fail on any internal identity/source marker. |
| D-002 | Keep the modular-monolith code and business behavior, but publish with a fresh Git history. | Existing repository contains internal history and removed evidence | The public remote must never be added as a push target to the internal repository. |
| D-003 | Keep version `0.1.0-SNAPSHOT` and state the remaining production gates. | Canonical `pom.xml` and internal release policy | No `1.0.0` tag or production claim is made. |
| D-004 | Keep sanitized reference manifests as executable public contract fixtures. | Frontend contract tests require the permission, notification and numbering catalogs | Manifests use only `showcase_catalog`, `example.invalid` and fictional labels; raw captures stay outside Git. |
| D-005 | Build portfolio records through the visible UI, including independent test confirmation and a nonconforming result. | User required user-simulated data rather than direct database injection | Portfolio screenshots demonstrate real authorization, workflow, audit and CAPA projections. |
| D-006 | Publish the repository as `lims` with the display name `LIMS（试验检测信息管理系统）`. | User-requested public repository rename on 2026-08-24 | README, CI links, Compose identity and GitHub metadata use the new name atomically; no compatibility alias or duplicate repository is retained. |

## Reusable Lessons
| ID | Symptom | Rejected path | Disproving evidence | Correct path | Prevention |
| --- | --- | --- | --- | --- | --- |
| L-001 | Deleted internal screenshots could still be recovered from Git history. | Push a sanitized branch of the internal repository | Git history retains removed blobs and original names. | Initialize a separate repository from a sanitized export. | Verify the public object database and denylist before every push. |
| L-002 | The first lifecycle script let the recording tester attempt to confirm the same record. | Add a delay or bypass the service rule | `TEST_REVIEWER_MUST_DIFFER` and the missing `TEST_CONFIRM` grant proved this was a duties-separation defect in the test. | Revise the reviewer role through the permissions UI, then confirm from a separate reviewer session. | Keep visible role assignment and independent-review assertions in the lifecycle E2E. |

## Verification Baseline
- Required commands/checks: `npm --prefix frontend run check`; `npm --prefix frontend run test`; `npm --prefix frontend run build`; `./mvnw verify`; public denylist and secret scans.
- Consumer and contract checks: empty PostgreSQL migration, Vue API build, Docker Compose config, visible browser screenshots from the sanitized runtime.
- Last verified revision/state: sanitized runtime on 2026-08-24; Java 21 `./mvnw verify` passed 82 tests against PostgreSQL 18.6 and Flyway V1–V56/V30–V56; Vue typecheck, 8 test files/21 tests and production build passed; Compose configuration validated; visible UI lifecycle passed in 45.4 seconds; 12 screenshots were captured at 1440×900 and passed visual/OCR denylist review; Gitleaks and Trivy secret scans reported zero findings. GitHub confirmed the public `main` repository at `2387452986/lims`; renamed README links and the `lims-public` Compose identity pass local diff/config validation, and CI run `32699033637` passed both frontend and backend jobs on the renamed repository.

## Open Questions
- Unresolved decision: None for the initial public snapshot.
- Why it blocks progress: None.

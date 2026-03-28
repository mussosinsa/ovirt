# storage_pool 테이블 레코드 강제 삭제 후 복구 방법

## 1. 개요

oVirt DB에서 `storage_pool` 레코드가 강제(`DELETE` SQL)로 삭제되면 다음과 같은 영향이 발생합니다.

| 영향 범위 | 동작 | 관련 테이블 |
|-----------|------|-------------|
| **CASCADE DELETE** | 연관 레코드 자동 삭제 | `iscsi_bonds`, `qos`, `quota`, `storage_pool_iso_map`, `vds_spm_id_map`, `vm_ovf_generations` |
| **SET NULL** | 참조 컬럼이 NULL로 변경 | `cluster.storage_pool_id`, `network.storage_pool_id` |
| **엔진 영향** | Data Center 조회 실패, SPM 연결 끊김 | oVirt Engine 서비스 오류 |

---

## 2. 사전 준비 — 삭제된 레코드 정보 파악

복구 전 반드시 삭제된 storage_pool의 UUID와 속성 값을 확인합니다.

### 2-1. 백업 DB에서 원본 레코드 조회

```sql
-- 백업 DB에서 원본 레코드 조회
SELECT
    id,
    name,
    description,
    storage_pool_type,
    storage_pool_format_type,
    status,
    master_domain_version,
    spm_vds_id,
    compatibility_version,
    _create_date,
    quota_enforcement_type,
    free_text_comment,
    is_local,
    mac_pool_id,
    managed
FROM storage_pool;
```

### 2-2. NULL 처리된 참조 테이블에서 UUID 추적

백업이 없는 경우, SET NULL 처리된 테이블에서 직접 pool ID를 역추적하기 어렵습니다.
`audit_log` 테이블의 `storage_pool_id` 컬럼에서 마지막으로 기록된 UUID를 조회합니다.

```sql
SELECT DISTINCT storage_pool_id, storage_pool_name
FROM audit_log
WHERE storage_pool_id IS NOT NULL
ORDER BY log_time DESC
LIMIT 10;
```

---

## 3. 복구 절차

### Step 1. oVirt Engine 서비스 중지

복구 작업 중 엔진이 DB를 수정하지 못하도록 서비스를 먼저 중지합니다.

```bash
systemctl stop ovirt-engine
```

### Step 2. storage_pool 레코드 재삽입

소스코드 기준 (`packaging/dbscripts/storages_sp.sql`)의 `Insertstorage_pool` 프로시저를 사용하거나,
아래와 같이 직접 INSERT합니다.

```sql
-- 트랜잭션 시작
BEGIN;

INSERT INTO storage_pool (
    id,
    name,
    description,
    storage_pool_type,
    storage_pool_format_type,
    status,
    master_domain_version,
    spm_vds_id,
    compatibility_version,
    _create_date,
    _update_date,
    quota_enforcement_type,
    free_text_comment,
    is_local,
    mac_pool_id,
    managed
) VALUES (
    '<삭제된_POOL_UUID>',          -- id (UUID)
    '<DATA_CENTER_NAME>',          -- name (최대 40자)
    '<DESCRIPTION>',               -- description
    1,                             -- storage_pool_type (1=iSCSI/FC 등)
    '<FORMAT_TYPE>',               -- storage_pool_format_type (예: 'V5', NULL 가능)
    0,                             -- status: 0=Uninitialized (안전한 초기 상태)
    0,                             -- master_domain_version
    NULL,                          -- spm_vds_id (SPM 호스트 UUID, 초기엔 NULL)
    '4.7',                         -- compatibility_version
    NOW(),                         -- _create_date
    NULL,                          -- _update_date
    NULL,                          -- quota_enforcement_type (NULL=Disabled)
    NULL,                          -- free_text_comment
    false,                         -- is_local
    '<MAC_POOL_UUID>',             -- mac_pool_id (mac_pools 테이블에서 확인)
    true                           -- managed
);
```

> **status 값 참조** (`StoragePoolStatus` enum):
> `0` = Uninitialized, `1` = Up, `2` = Maintenance, `3` = NonResponsive, `4` = NotOperational, `5` = Problematic

### Step 3. cluster 테이블 참조 복구

`cluster.storage_pool_id`가 NULL로 변경된 클러스터를 복구합니다.

```sql
-- NULL 처리된 클러스터 확인
SELECT cluster_id, name, storage_pool_id FROM cluster WHERE storage_pool_id IS NULL;

-- 복구 (해당 클러스터가 이 Data Center에 속했던 경우)
UPDATE cluster
SET storage_pool_id = '<삭제된_POOL_UUID>'
WHERE cluster_id = '<CLUSTER_UUID>';
```

### Step 4. network 테이블 참조 복구

```sql
-- NULL 처리된 네트워크 확인
SELECT id, name, storage_pool_id FROM network WHERE storage_pool_id IS NULL;

-- 복구
UPDATE network
SET storage_pool_id = '<삭제된_POOL_UUID>'
WHERE id = '<NETWORK_UUID>';
```

### Step 5. storage_pool_iso_map 재삽입 (스토리지 도메인 연결 복구)

CASCADE DELETE로 삭제된 스토리지 도메인 매핑을 복구합니다.

```sql
-- 백업에서 조회하거나 storage_domain_static에서 도메인 목록 확인
SELECT id, storage_name, storage_type FROM storage_domain_static;

-- 매핑 재삽입 (status: 1=Active, 2=Maintenance, 3=Locked, 4=Inactive)
INSERT INTO storage_pool_iso_map (storage_id, storage_pool_id, status)
VALUES
    ('<MASTER_DOMAIN_UUID>', '<삭제된_POOL_UUID>', 1),   -- Master domain
    ('<DATA_DOMAIN_UUID>',   '<삭제된_POOL_UUID>', 1);   -- Data domain
```

### Step 6. quota (기본 할당량) 재삽입

`AddEmptyStoragePoolCommand.java`의 `addDefaultQuotaToDb()` 로직을 참고하여
기본 무제한 Quota를 복구합니다.

```sql
-- 기본 Quota 재삽입
INSERT INTO quota (
    id,
    storage_pool_id,
    quota_name,
    description,
    is_default,
    threshold_cluster_percentage,
    threshold_storage_percentage,
    grace_cluster_percentage,
    grace_storage_percentage
) VALUES (
    gen_random_uuid(),
    '<삭제된_POOL_UUID>',
    'Default',
    'Default unlimited quota',
    true,
    80,    -- threshold_cluster_percentage (기본값)
    80,    -- threshold_storage_percentage
    20,    -- grace_cluster_percentage
    20     -- grace_storage_percentage
);
```

### Step 7. vds_spm_id_map 재삽입 (선택)

SPM 매핑 정보를 복구합니다. 백업 데이터가 없으면 엔진 재시작 후 SPM 선출 과정에서 자동 재생성됩니다.

```sql
-- 백업에서 복구 가능한 경우
INSERT INTO vds_spm_id_map (storage_pool_id, vds_spm_id, vds_id)
SELECT '<삭제된_POOL_UUID>', vds_spm_id, vds_id
FROM <backup_vds_spm_id_map>;
```

### Step 8. vm_ovf_generations 재삽입 (선택)

OVF 생성 정보는 즉시 복구가 필수적이지 않으며, 엔진 재시작 후 VM 상태 동기화 시 재생성됩니다.

### Step 9. 트랜잭션 커밋 및 엔진 재시작

```sql
-- 이상 없으면 커밋
COMMIT;

-- 문제 발생 시 롤백
-- ROLLBACK;
```

```bash
# 엔진 서비스 재시작
systemctl start ovirt-engine

# 로그 모니터링
tail -f /var/log/ovirt-engine/engine.log | grep -i "storage_pool\|DataCenter"
```

---

## 4. 복구 후 검증

### 4-1. DB에서 복구 상태 확인

```sql
-- storage_pool 레코드 확인
SELECT id, name, status FROM storage_pool WHERE id = '<삭제된_POOL_UUID>';

-- 연관 클러스터 확인
SELECT cluster_id, name FROM cluster WHERE storage_pool_id = '<삭제된_POOL_UUID>';

-- 스토리지 도메인 매핑 확인
SELECT storage_id, status FROM storage_pool_iso_map WHERE storage_pool_id = '<삭제된_POOL_UUID>';

-- Quota 확인
SELECT id, quota_name, is_default FROM quota WHERE storage_pool_id = '<삭제된_POOL_UUID>';
```

### 4-2. oVirt Engine UI 확인

1. oVirt Admin Portal 접속
2. **Data Centers** 탭에서 복구된 Data Center 확인
3. 상태가 `Uninitialized` 또는 `NonResponsive`인 경우 정상 — SPM 재선출 후 `Up` 상태로 전환

### 4-3. RecoveryStoragePool 명령 활용

UI에서 Data Center가 `NonResponsive` 상태인 경우:
`RecoveryStoragePoolCommand.java` 로직에 따라 **Reconstruct Master Domain** 작업을 실행합니다.

- Admin Portal → Data Centers → 해당 DC 선택 → More Actions → **Reconstruct Master Domain**

---

## 5. 핵심 파일 참조

| 역할 | 파일 경로 |
|------|-----------|
| 테이블 DDL 및 FK 제약 | `packaging/dbscripts/create_tables.sql` |
| CRUD 저장 프로시저 | `packaging/dbscripts/storages_sp.sql` |
| 기본 데이터 INSERT | `packaging/dbscripts/data/00300_insert_storage_pool.sql` |
| Java 도메인 엔티티 | `backend/.../common/businessentities/StoragePool.java` |
| 풀 생성 Command | `backend/.../bll/storage/pool/AddEmptyStoragePoolCommand.java` |
| 복구 Command | `backend/.../bll/storage/pool/RecoveryStoragePoolCommand.java` |
| 삭제 Command | `backend/.../bll/storage/pool/RemoveStoragePoolCommand.java` |

---

## 6. 주의사항

- `mac_pool_id`는 `mac_pools` 테이블에 반드시 존재하는 UUID여야 합니다 (`NOT NULL` 제약).
- `status`를 `0` (Uninitialized)으로 설정하면 엔진이 초기화 대기 상태로 인식하여 안전하게 복구 절차를 진행할 수 있습니다.
- 백업 없이 복구 시 `audit_log` 테이블의 `storage_pool_id` 컬럼이 UUID 추적의 유일한 단서입니다.
- `vm_ovf_generations`와 `vds_spm_id_map`은 엔진 재시작 시 자동 재생성되므로 우선순위가 낮습니다.

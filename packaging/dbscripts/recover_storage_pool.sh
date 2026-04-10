#!/bin/bash
# =============================================================================
# recover_storage_pool.sh
# oVirt DB의 storage_pool 레코드 강제 삭제 후 복구 자동화 스크립트
#
# 사용법:
#   chmod +x recover_storage_pool.sh
#   ./recover_storage_pool.sh [옵션]
#
# 옵션:
#   --pool-id     UUID      복구할 storage_pool UUID (필수)
#   --name        STRING    Data Center 이름 (필수)
#   --desc        STRING    설명 (기본값: "")
#   --compat-ver  STRING    호환 버전 (기본값: 4.7)
#   --is-local    BOOL      로컬 스토리지 여부 (기본값: false)
#   --mac-pool-id UUID      mac_pool UUID (미지정 시 기본 MAC pool 자동 선택)
#   --dry-run               실제 변경 없이 실행될 SQL만 출력
#   --skip-engine-stop      oVirt Engine 서비스 중지 건너뜀
#   --help                  도움말 출력
#
# 예시:
#   ./recover_storage_pool.sh \
#     --pool-id   "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" \
#     --name      "MyDataCenter" \
#     --compat-ver "4.7"
# =============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# 상수
# -----------------------------------------------------------------------------
readonly SCRIPT_NAME="$(basename "$0")"
readonly LOG_FILE="/var/log/ovirt-engine/storage-pool-recovery-$(date +%Y%m%d_%H%M%S).log"
readonly ENGINE_SERVICE="ovirt-engine"
readonly ENGINE_LOG="/var/log/ovirt-engine/engine.log"

# StoragePoolStatus enum (AddEmptyStoragePoolCommand.java 기준)
readonly STATUS_UNINITIALIZED=0
readonly STATUS_UP=1
readonly STATUS_MAINTENANCE=2

# quota 기본값 (AddEmptyStoragePoolCommand.java::addDefaultQuotaToDb 기준)
readonly DEFAULT_QUOTA_THRESHOLD_CLUSTER=80
readonly DEFAULT_QUOTA_THRESHOLD_STORAGE=80
readonly DEFAULT_QUOTA_GRACE_CLUSTER=20
readonly DEFAULT_QUOTA_GRACE_STORAGE=20

# quota_limitation 무제한 값 (QuotaCluster.UNLIMITED_MEM, QuotaStorage.UNLIMITED 기준)
readonly QUOTA_UNLIMITED_VCPU=-1
readonly QUOTA_UNLIMITED_MEM=-1
readonly QUOTA_UNLIMITED_STORAGE=-1

# -----------------------------------------------------------------------------
# 전역 변수 (인자로 설정)
# -----------------------------------------------------------------------------
POOL_ID=""
POOL_NAME=""
POOL_DESC=""
COMPAT_VER="4.7"
IS_LOCAL="false"
MAC_POOL_ID=""
DRY_RUN=false
SKIP_ENGINE_STOP=false

# DB 접속 정보 (engine-config 또는 환경 변수에서 읽음)
DB_HOST="${ENGINE_DB_HOST:-localhost}"
DB_PORT="${ENGINE_DB_PORT:-5432}"
DB_NAME="${ENGINE_DB_NAME:-engine}"
DB_USER="${ENGINE_DB_USER:-engine}"
PGPASSWORD="${ENGINE_DB_PASSWORD:-}"
export PGPASSWORD

# -----------------------------------------------------------------------------
# 로깅 함수
# -----------------------------------------------------------------------------
log()  { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [INFO]  $*" | tee -a "$LOG_FILE"; }
warn() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [WARN]  $*" | tee -a "$LOG_FILE" >&2; }
err()  { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ERROR] $*" | tee -a "$LOG_FILE" >&2; }
die()  { err "$*"; exit 1; }

# -----------------------------------------------------------------------------
# 사용법 출력
# -----------------------------------------------------------------------------
usage() {
    cat <<EOF
사용법: $SCRIPT_NAME [옵션]

필수 옵션:
  --pool-id   <UUID>    복구할 storage_pool UUID
  --name      <STRING>  Data Center 이름 (최대 40자)

선택 옵션:
  --desc        <STRING>  설명 (기본값: "")
  --compat-ver  <STRING>  호환 버전 (기본값: 4.7)
  --is-local    <BOOL>    로컬 스토리지 여부 (기본값: false)
  --mac-pool-id <UUID>    MAC pool UUID (미지정 시 기본 MAC pool 자동 선택)
  --dry-run               SQL 출력만 하고 실제 적용하지 않음
  --skip-engine-stop      oVirt Engine 서비스 중지 건너뜀
  -h, --help              도움말 출력

DB 접속 환경 변수:
  ENGINE_DB_HOST      (기본값: localhost)
  ENGINE_DB_PORT      (기본값: 5432)
  ENGINE_DB_NAME      (기본값: engine)
  ENGINE_DB_USER      (기본값: engine)
  ENGINE_DB_PASSWORD

예시:
  $SCRIPT_NAME \\
    --pool-id   "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" \\
    --name      "Production-DC" \\
    --compat-ver "4.7"

  # 실제 적용 없이 SQL 확인
  $SCRIPT_NAME --pool-id "..." --name "TestDC" --dry-run
EOF
}

# -----------------------------------------------------------------------------
# 인자 파싱
# -----------------------------------------------------------------------------
parse_args() {
    [[ $# -eq 0 ]] && { usage; exit 0; }

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --pool-id)        POOL_ID="$2";       shift 2 ;;
            --name)           POOL_NAME="$2";     shift 2 ;;
            --desc)           POOL_DESC="$2";     shift 2 ;;
            --compat-ver)     COMPAT_VER="$2";    shift 2 ;;
            --is-local)       IS_LOCAL="$2";      shift 2 ;;
            --mac-pool-id)    MAC_POOL_ID="$2";   shift 2 ;;
            --dry-run)        DRY_RUN=true;       shift ;;
            --skip-engine-stop) SKIP_ENGINE_STOP=true; shift ;;
            -h|--help)        usage; exit 0 ;;
            *) die "알 수 없는 옵션: $1. --help 로 사용법을 확인하세요." ;;
        esac
    done

    [[ -z "$POOL_ID" ]]   && die "--pool-id 는 필수 옵션입니다."
    [[ -z "$POOL_NAME" ]] && die "--name 은 필수 옵션입니다."

    # UUID 형식 검증
    if ! [[ "$POOL_ID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
        die "--pool-id 가 올바른 UUID 형식이 아닙니다: $POOL_ID"
    fi

    # 이름 길이 제한 (create_tables.sql: VARCHAR(40))
    if [[ ${#POOL_NAME} -gt 40 ]]; then
        die "--name 은 최대 40자까지 허용됩니다."
    fi
}

# -----------------------------------------------------------------------------
# psql 래퍼
# -----------------------------------------------------------------------------
run_sql() {
    local sql="$1"
    if $DRY_RUN; then
        echo "-- [DRY-RUN] 실행 예정 SQL:"
        echo "$sql"
        echo ""
        return 0
    fi
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
         -v ON_ERROR_STOP=1 \
         -c "$sql" 2>&1 | tee -a "$LOG_FILE"
}

run_sql_query() {
    local sql="$1"
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
         -v ON_ERROR_STOP=1 -t -A \
         -c "$sql" 2>/dev/null
}

# -----------------------------------------------------------------------------
# DB 접속 확인
# -----------------------------------------------------------------------------
check_db_connection() {
    log "DB 접속 확인 중... (${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME})"
    if ! psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
              -c "SELECT 1;" &>/dev/null; then
        die "DB 접속에 실패했습니다. DB 접속 정보(환경 변수)를 확인하세요."
    fi
    log "DB 접속 성공."
}

# -----------------------------------------------------------------------------
# 사전 진단
# -----------------------------------------------------------------------------
preflight_checks() {
    log "========== 사전 진단 시작 =========="

    # 1) storage_pool 레코드 존재 여부 확인
    local exists
    exists=$(run_sql_query "SELECT COUNT(*) FROM storage_pool WHERE id = '${POOL_ID}';")
    if [[ "$exists" -gt 0 ]]; then
        die "storage_pool 레코드가 이미 존재합니다 (id=${POOL_ID}). 복구가 필요하지 않습니다."
    fi
    log "확인: storage_pool 레코드 없음 — 복구 필요."

    # 2) NULL 처리된 cluster 목록 출력 (audit_log로 UUID 추적)
    log "audit_log에서 삭제된 pool UUID 추적 시도..."
    local audit_result
    audit_result=$(run_sql_query \
        "SELECT DISTINCT storage_pool_id, storage_pool_name
         FROM audit_log
         WHERE storage_pool_id = '${POOL_ID}'
         ORDER BY storage_pool_id
         LIMIT 5;") || true
    if [[ -n "$audit_result" ]]; then
        log "audit_log에서 pool UUID 확인됨: $audit_result"
    else
        warn "audit_log에서 해당 pool UUID를 찾을 수 없습니다. UUID가 정확한지 확인하세요."
    fi

    # 3) NULL 처리된 cluster 목록 출력
    local null_clusters
    null_clusters=$(run_sql_query \
        "SELECT cluster_id || ' (' || name || ')' FROM cluster WHERE storage_pool_id IS NULL;") || true
    if [[ -n "$null_clusters" ]]; then
        warn "storage_pool_id가 NULL인 클러스터 발견 (복구 대상):"
        echo "$null_clusters" | while IFS= read -r line; do warn "  - $line"; done
    fi

    # 4) NULL 처리된 network 목록 출력
    local null_networks
    null_networks=$(run_sql_query \
        "SELECT id || ' (' || name || ')' FROM network WHERE storage_pool_id IS NULL;") || true
    if [[ -n "$null_networks" ]]; then
        warn "storage_pool_id가 NULL인 네트워크 발견 (복구 대상):"
        echo "$null_networks" | while IFS= read -r line; do warn "  - $line"; done
    fi

    # 5) mac_pool_id 결정
    if [[ -z "$MAC_POOL_ID" ]]; then
        log "mac_pool_id 미지정 — 기본 MAC pool 자동 선택..."
        MAC_POOL_ID=$(run_sql_query \
            "SELECT id FROM mac_pools WHERE default_pool = true LIMIT 1;") || true
        if [[ -z "$MAC_POOL_ID" ]]; then
            MAC_POOL_ID=$(run_sql_query "SELECT id FROM mac_pools LIMIT 1;") || true
        fi
        if [[ -z "$MAC_POOL_ID" ]]; then
            die "mac_pools 테이블에 레코드가 없습니다. --mac-pool-id 로 직접 지정하세요."
        fi
        log "선택된 mac_pool_id: ${MAC_POOL_ID}"
    else
        local mac_exists
        mac_exists=$(run_sql_query "SELECT COUNT(*) FROM mac_pools WHERE id = '${MAC_POOL_ID}';") || true
        [[ "$mac_exists" -eq 0 ]] && die "mac_pool_id '${MAC_POOL_ID}'가 mac_pools 테이블에 존재하지 않습니다."
    fi

    log "========== 사전 진단 완료 =========="
}

# -----------------------------------------------------------------------------
# oVirt Engine 서비스 중지/시작
# -----------------------------------------------------------------------------
stop_engine() {
    if $SKIP_ENGINE_STOP; then
        warn "오옵션으로 Engine 서비스 중지를 건너뜁니다. (위험: 복구 중 엔진이 DB를 수정할 수 있습니다)"
        return
    fi
    if $DRY_RUN; then
        log "[DRY-RUN] systemctl stop ${ENGINE_SERVICE} 실행 예정"
        return
    fi
    if systemctl is-active --quiet "$ENGINE_SERVICE"; then
        log "oVirt Engine 서비스 중지 중..."
        systemctl stop "$ENGINE_SERVICE"
        log "oVirt Engine 서비스 중지 완료."
    else
        log "oVirt Engine 서비스가 이미 중지 상태입니다."
    fi
}

start_engine() {
    if $SKIP_ENGINE_STOP; then return; fi
    if $DRY_RUN; then
        log "[DRY-RUN] systemctl start ${ENGINE_SERVICE} 실행 예정"
        return
    fi
    log "oVirt Engine 서비스 재시작 중..."
    systemctl start "$ENGINE_SERVICE"
    log "oVirt Engine 서비스 재시작 완료."
    log "엔진 로그 모니터링: tail -f ${ENGINE_LOG} | grep -i 'storage_pool\\|DataCenter'"
}

# -----------------------------------------------------------------------------
# Step 1: storage_pool 레코드 재삽입
# -----------------------------------------------------------------------------
recover_storage_pool_record() {
    log "---------- Step 1: storage_pool 레코드 재삽입 ----------"

    # sql escape
    local esc_name="${POOL_NAME//\'/\'\'}"
    local esc_desc="${POOL_DESC//\'/\'\'}"

    run_sql "
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
    '${POOL_ID}',
    '${esc_name}',
    '${esc_desc}',
    1,
    NULL,
    ${STATUS_UNINITIALIZED},
    0,
    NULL,
    '${COMPAT_VER}',
    NOW(),
    NULL,
    NULL,
    NULL,
    ${IS_LOCAL},
    '${MAC_POOL_ID}',
    true
);"

    log "storage_pool 레코드 삽입 완료: id=${POOL_ID}, name=${POOL_NAME}, status=Uninitialized(0)"
}

# -----------------------------------------------------------------------------
# Step 2: quota 재삽입 (AddEmptyStoragePoolCommand::addDefaultQuotaToDb 참조)
# -----------------------------------------------------------------------------
recover_quota() {
    log "---------- Step 2: 기본 Quota 재삽입 ----------"

    # 이미 quota가 있으면 건너뜀
    local existing
    existing=$(run_sql_query \
        "SELECT COUNT(*) FROM quota WHERE storage_pool_id = '${POOL_ID}';") || true

    if [[ "$existing" -gt 0 ]]; then
        log "이미 quota 레코드가 존재합니다 (${existing}건). 건너뜁니다."
        return
    fi

    local quota_id
    if $DRY_RUN; then
        quota_id="<gen_random_uuid()>"
    else
        quota_id=$(run_sql_query "SELECT gen_random_uuid();")
    fi

    local quota_limit_id_cluster
    local quota_limit_id_storage
    if $DRY_RUN; then
        quota_limit_id_cluster="<gen_random_uuid()>"
        quota_limit_id_storage="<gen_random_uuid()>"
    else
        quota_limit_id_cluster=$(run_sql_query "SELECT gen_random_uuid();")
        quota_limit_id_storage=$(run_sql_query "SELECT gen_random_uuid();")
    fi

    # 기본 Quota 삽입
    run_sql "
INSERT INTO quota (
    id,
    storage_pool_id,
    quota_name,
    description,
    threshold_cluster_percentage,
    threshold_storage_percentage,
    grace_cluster_percentage,
    grace_storage_percentage,
    is_default
) VALUES (
    '${quota_id}',
    '${POOL_ID}',
    'Default',
    'Default unlimited quota',
    ${DEFAULT_QUOTA_THRESHOLD_CLUSTER},
    ${DEFAULT_QUOTA_THRESHOLD_STORAGE},
    ${DEFAULT_QUOTA_GRACE_CLUSTER},
    ${DEFAULT_QUOTA_GRACE_STORAGE},
    true
);"

    # 글로벌 무제한 cluster quota_limitation (cluster_id=NULL → 글로벌)
    run_sql "
INSERT INTO quota_limitation (id, quota_id, storage_id, cluster_id, virtual_cpu, mem_size_mb, storage_size_gb)
VALUES (
    '${quota_limit_id_cluster}',
    '${quota_id}',
    NULL,
    NULL,
    ${QUOTA_UNLIMITED_VCPU},
    ${QUOTA_UNLIMITED_MEM},
    NULL
);"

    # 글로벌 무제한 storage quota_limitation (storage_id=NULL → 글로벌)
    run_sql "
INSERT INTO quota_limitation (id, quota_id, storage_id, cluster_id, virtual_cpu, mem_size_mb, storage_size_gb)
VALUES (
    '${quota_limit_id_storage}',
    '${quota_id}',
    NULL,
    NULL,
    NULL,
    NULL,
    ${QUOTA_UNLIMITED_STORAGE}
);"

    log "기본 Quota 삽입 완료: quota_id=${quota_id}"
}

# -----------------------------------------------------------------------------
# Step 3: cluster 참조 복구
# -----------------------------------------------------------------------------
recover_clusters() {
    log "---------- Step 3: cluster.storage_pool_id 참조 복구 ----------"

    local null_cluster_ids
    null_cluster_ids=$(run_sql_query \
        "SELECT cluster_id FROM cluster WHERE storage_pool_id IS NULL;") || true

    if [[ -z "$null_cluster_ids" ]]; then
        log "storage_pool_id가 NULL인 클러스터가 없습니다. 건너뜁니다."
        return
    fi

    local count=0
    while IFS= read -r cid; do
        [[ -z "$cid" ]] && continue
        local cname
        cname=$(run_sql_query "SELECT name FROM cluster WHERE cluster_id = '${cid}';") || true
        log "클러스터 복구: ${cid} (${cname})"
        run_sql "UPDATE cluster SET storage_pool_id = '${POOL_ID}' WHERE cluster_id = '${cid}';"
        ((count++))
    done <<< "$null_cluster_ids"

    log "클러스터 복구 완료: ${count}건"
}

# -----------------------------------------------------------------------------
# Step 4: network 참조 복구
# -----------------------------------------------------------------------------
recover_networks() {
    log "---------- Step 4: network.storage_pool_id 참조 복구 ----------"

    local null_network_ids
    null_network_ids=$(run_sql_query \
        "SELECT id FROM network WHERE storage_pool_id IS NULL;") || true

    if [[ -z "$null_network_ids" ]]; then
        log "storage_pool_id가 NULL인 네트워크가 없습니다. 건너뜁니다."
        return
    fi

    local count=0
    while IFS= read -r nid; do
        [[ -z "$nid" ]] && continue
        local nname
        nname=$(run_sql_query "SELECT name FROM network WHERE id = '${nid}';") || true
        log "네트워크 복구: ${nid} (${nname})"
        run_sql "UPDATE network SET storage_pool_id = '${POOL_ID}' WHERE id = '${nid}';"
        ((count++))
    done <<< "$null_network_ids"

    log "네트워크 복구 완료: ${count}건"
}

# -----------------------------------------------------------------------------
# Step 5: storage_pool_iso_map 확인 (CASCADE 삭제된 도메인 매핑 안내)
# -----------------------------------------------------------------------------
report_iso_map() {
    log "---------- Step 5: storage_pool_iso_map 상태 확인 ----------"

    local map_count
    map_count=$(run_sql_query \
        "SELECT COUNT(*) FROM storage_pool_iso_map WHERE storage_pool_id = '${POOL_ID}';") || true

    if [[ "$map_count" -gt 0 ]]; then
        log "storage_pool_iso_map에 ${map_count}건의 도메인 매핑이 존재합니다."
    else
        warn "storage_pool_iso_map에 도메인 매핑이 없습니다."
        warn "스토리지 도메인이 있다면 아래 SQL로 수동 복구하세요:"
        warn "  INSERT INTO storage_pool_iso_map (storage_id, storage_pool_id, status)"
        warn "  VALUES ('<STORAGE_DOMAIN_UUID>', '${POOL_ID}', 1);"
        warn ""
        warn "  -- 사용 가능한 스토리지 도메인 목록 조회:"
        warn "  SELECT id, storage_name FROM storage_domain_static;"
    fi
}

# -----------------------------------------------------------------------------
# 복구 후 검증
# -----------------------------------------------------------------------------
verify_recovery() {
    log "========== 복구 후 검증 =========="

    local pool_row
    pool_row=$(run_sql_query \
        "SELECT id || ' | name=' || name || ' | status=' || status
         FROM storage_pool WHERE id = '${POOL_ID}';") || true

    if [[ -n "$pool_row" ]]; then
        log "storage_pool 레코드 확인: $pool_row"
    else
        err "storage_pool 레코드가 DB에 없습니다! 복구에 실패했을 수 있습니다."
    fi

    local quota_count
    quota_count=$(run_sql_query \
        "SELECT COUNT(*) FROM quota WHERE storage_pool_id = '${POOL_ID}';") || true
    log "quota 레코드 수: ${quota_count}"

    local cluster_count
    cluster_count=$(run_sql_query \
        "SELECT COUNT(*) FROM cluster WHERE storage_pool_id = '${POOL_ID}';") || true
    log "연결된 cluster 수: ${cluster_count}"

    local network_count
    network_count=$(run_sql_query \
        "SELECT COUNT(*) FROM network WHERE storage_pool_id = '${POOL_ID}';") || true
    log "연결된 network 수: ${network_count}"

    local iso_map_count
    iso_map_count=$(run_sql_query \
        "SELECT COUNT(*) FROM storage_pool_iso_map WHERE storage_pool_id = '${POOL_ID}';") || true
    log "storage_pool_iso_map 매핑 수: ${iso_map_count}"

    log "========== 검증 완료 =========="
}

# -----------------------------------------------------------------------------
# 메인 흐름
# -----------------------------------------------------------------------------
main() {
    # 로그 파일 디렉토리 생성 (권한 없으면 /tmp에 기록)
    mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || LOG_FILE="/tmp/storage-pool-recovery-$(date +%Y%m%d_%H%M%S).log"

    parse_args "$@"

    log "============================================================"
    log "oVirt storage_pool 복구 스크립트 시작"
    log "  pool_id    : ${POOL_ID}"
    log "  name       : ${POOL_NAME}"
    log "  compat_ver : ${COMPAT_VER}"
    log "  is_local   : ${IS_LOCAL}"
    log "  dry_run    : ${DRY_RUN}"
    log "  log_file   : ${LOG_FILE}"
    log "============================================================"

    check_db_connection
    preflight_checks

    stop_engine

    if ! $DRY_RUN; then
        # 트랜잭션으로 전체 복구를 묶음
        run_sql "BEGIN;"
    fi

    recover_storage_pool_record
    recover_quota
    recover_clusters
    recover_networks

    if ! $DRY_RUN; then
        run_sql "COMMIT;"
        log "트랜잭션 커밋 완료."
    fi

    report_iso_map
    verify_recovery
    start_engine

    log "============================================================"
    log "복구 완료. 로그 파일: ${LOG_FILE}"
    if [[ "${iso_map_count:-0}" -eq 0 ]] 2>/dev/null; then
        warn "스토리지 도메인 매핑(storage_pool_iso_map)이 없습니다."
        warn "Admin Portal에서 Reconstruct Master Domain 작업을 실행하세요:"
        warn "  Data Centers → ${POOL_NAME} → More Actions → Reconstruct Master Domain"
    fi
    log "============================================================"
}

main "$@"

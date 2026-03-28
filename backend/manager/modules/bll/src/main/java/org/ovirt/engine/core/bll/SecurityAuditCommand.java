package org.ovirt.engine.core.bll;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.ActionParametersBase;
import org.ovirt.engine.core.common.businessentities.ActionGroup;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogDirector;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogable;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogableImpl;
import org.ovirt.engine.core.dao.AuditLogDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command to execute security audit script and log results
 */
public class SecurityAuditCommand<T extends ActionParametersBase> extends CommandBase<T> {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditCommand.class);
    private static final String SECURITY_AUDIT_SCRIPT = "/usr/share/ovirt-engine/bin/ov-works-security_audit.sh"; //$NON-NLS-1$

    @Inject
    private AuditLogDao auditLogDao;

    @Inject
    private AuditLogDirector auditLogDirector;

    public SecurityAuditCommand(T parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    @Override
    protected boolean validate() {
        return true;
    }

    @Override
    protected void executeCommand() {
        // Check if script exists and is executable
        java.io.File scriptFile = new java.io.File(SECURITY_AUDIT_SCRIPT);
        if (!scriptFile.exists()) {
            String errorMsg = "보안 감사 스크립트를 찾을 수 없습니다: " + SECURITY_AUDIT_SCRIPT;
            log.error("Security audit script not found: {}", SECURITY_AUDIT_SCRIPT);
            logAuditEvent(AuditLogType.SECURITY_AUDIT_FAILED, "Security audit script not found: " + SECURITY_AUDIT_SCRIPT);
            getReturnValue().getExecuteFailedMessages().add(errorMsg);
            setSucceeded(false);
            return;
        }
        if (!scriptFile.canExecute()) {
            String errorMsg = "보안 감사 스크립트를 실행할 수 없습니다: " + SECURITY_AUDIT_SCRIPT;
            log.error("Security audit script is not executable: {}", SECURITY_AUDIT_SCRIPT);
            logAuditEvent(AuditLogType.SECURITY_AUDIT_FAILED, "Security audit script is not executable: " + SECURITY_AUDIT_SCRIPT);
            getReturnValue().getExecuteFailedMessages().add(errorMsg);
            setSucceeded(false);
            return;
        }

        logAuditEvent(AuditLogType.SECURITY_AUDIT_STARTED, "Security audit started");

        try {
            // Execute the security audit script
            ProcessBuilder processBuilder = new ProcessBuilder("sh", SECURITY_AUDIT_SCRIPT);
            processBuilder.environment().put("SECURITY_AUDIT_STRICT", "0");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.info("Security Audit: {}", line);
                }
            }

            // Wait for completion
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                logAuditEvent(AuditLogType.SECURITY_AUDIT_COMPLETED, "Security audit completed successfully");
                setSucceeded(true);
            } else {
                String errorMsg = "보안 감사 실패 (종료 코드: " + exitCode + ")\n" + output.toString();
                logAuditEvent(AuditLogType.SECURITY_AUDIT_FAILED,
                    "Security audit failed with exit code: " + exitCode);
                getReturnValue().getExecuteFailedMessages().add(errorMsg);
                setSucceeded(false);
            }

            // Store the output in return value
            getReturnValue().setActionReturnValue(output.toString());

        } catch (Exception e) {
            String errorMsg = "보안 감사 실행 중 오류 발생: " + e.getMessage();
            log.error("Failed to execute security audit script", e);
            logAuditEvent(AuditLogType.SECURITY_AUDIT_FAILED,
                "Security audit failed with error: " + e.getMessage());
            getReturnValue().getExecuteFailedMessages().add(errorMsg);
            setSucceeded(false);
        }
    }

    private void logAuditEvent(AuditLogType type, String message) {
        AuditLogable logable = new AuditLogableImpl();
        logable.setUserId(getCurrentUser().getId());
        logable.setUserName(getCurrentUser().getLoginName());
        logable.setCustomData(message);
        auditLogDirector.log(logable, type);
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        return Collections.singletonList(new PermissionSubject(Guid.SYSTEM,
                VdcObjectType.System,
                ActionGroup.AUDIT_LOG_MANAGEMENT));
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        return getSucceeded() ? AuditLogType.SECURITY_AUDIT_COMPLETED : AuditLogType.SECURITY_AUDIT_FAILED;
    }
}

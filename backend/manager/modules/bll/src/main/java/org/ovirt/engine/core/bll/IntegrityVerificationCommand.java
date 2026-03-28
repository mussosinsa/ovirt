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
 * Command to verify integrity using AIDE.
 */
public class IntegrityVerificationCommand<T extends ActionParametersBase> extends CommandBase<T> {

    private static final Logger log = LoggerFactory.getLogger(IntegrityVerificationCommand.class);
    private static final String SUDO_COMMAND = "/usr/bin/sudo"; //$NON-NLS-1$
    private static final String INTEGRITY_CHECK_COMMAND = "/usr/sbin/aide"; //$NON-NLS-1$
    private static final String INTEGRITY_CHECK_OPTION = "--check"; //$NON-NLS-1$

    @Inject
    private AuditLogDao auditLogDao;

    @Inject
    private AuditLogDirector auditLogDirector;

    public IntegrityVerificationCommand(T parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    @Override
    protected boolean validate() {
        return true;
    }

    @Override
    protected void executeCommand() {
        logAuditEvent(AuditLogType.INTEGRITY_VERIFICATION_STARTED, "Integrity verification started");

        try {
            // Use sudo to run AIDE with proper privileges to access protected files
            ProcessBuilder processBuilder = new ProcessBuilder(
                    SUDO_COMMAND, "-n", INTEGRITY_CHECK_COMMAND, INTEGRITY_CHECK_OPTION); //$NON-NLS-1$
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n"); //$NON-NLS-1$
                    log.info("Integrity verification: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logAuditEvent(AuditLogType.INTEGRITY_VERIFICATION_COMPLETED,
                        "Integrity verification completed successfully");
                setSucceeded(true);
            } else {
                String errorMsg = "무결성 검사 실패 (종료 코드: " + exitCode + ")\n" + output.toString();
                logAuditEvent(AuditLogType.INTEGRITY_VERIFICATION_FAILED,
                        "Integrity verification failed with exit code: " + exitCode);
                getReturnValue().getExecuteFailedMessages().add(errorMsg);
                setSucceeded(false);
            }

            getReturnValue().setActionReturnValue(output.toString());
        } catch (Exception e) {
            String errorMsg = "무결성 검사 실행 중 오류 발생: " + e.getMessage();
            log.error("Failed to execute integrity verification", e);
            logAuditEvent(AuditLogType.INTEGRITY_VERIFICATION_FAILED,
                    "Integrity verification failed with error: " + e.getMessage());
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
        return getSucceeded() ? AuditLogType.INTEGRITY_VERIFICATION_COMPLETED : AuditLogType.INTEGRITY_VERIFICATION_FAILED;
    }
}

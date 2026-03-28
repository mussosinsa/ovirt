package org.ovirt.engine.core.bll;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.AuditLogBackupParameters;
import org.ovirt.engine.core.common.businessentities.ActionGroup;
import org.ovirt.engine.core.compat.Guid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoteBackupCommand extends CommandBase<AuditLogBackupParameters> {

    private static final Logger log = LoggerFactory.getLogger(RemoteBackupCommand.class);
    private static final String SUDO_COMMAND = "/usr/bin/sudo"; //$NON-NLS-1$
    private static final String SH_COMMAND = "/bin/sh"; //$NON-NLS-1$
    private static final String SYSTEMCTL_COMMAND = "/bin/systemctl"; //$NON-NLS-1$
    private static final String RSYSLOG_CONF = "/etc/rsyslog.conf"; //$NON-NLS-1$
    private static final String RSYSLOG_MARKER = "# ov-works audit log remote backup"; //$NON-NLS-1$

    public RemoteBackupCommand(AuditLogBackupParameters parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    @Override
    protected void executeCommand() {
        String remoteAddress = getParameters().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.trim().isEmpty()) {
            getReturnValue().getExecuteFailedMessages().add("원격 서버 주소가 비어 있습니다."); //$NON-NLS-1$
            setSucceeded(false);
            return;
        }

        String block = RSYSLOG_MARKER + "\n" //$NON-NLS-1$
                + "module(load=\"imfile\")\n" //$NON-NLS-1$
                + "input(type=\"imfile\" File=\"/var/log/ovirt-engine/*.log\" Tag=\"ovirt-engine\" " //$NON-NLS-1$
                + "Severity=\"info\" Facility=\"local0\")\n" //$NON-NLS-1$
                + "local0.* @@"+ remoteAddress.trim() + "\n"; //$NON-NLS-1$ //$NON-NLS-2$

        String escapedBlock = escapeForSingleQuotes(block);
        String addCommand = "grep -Fq '" + RSYSLOG_MARKER + "' " + RSYSLOG_CONF //$NON-NLS-1$ //$NON-NLS-2$
                + " || printf '%s' '" + escapedBlock + "' >> " + RSYSLOG_CONF; //$NON-NLS-1$ //$NON-NLS-2$

        CommandResult addResult = runCommand(Arrays.asList(
                SUDO_COMMAND, "-n", SH_COMMAND, "-c", addCommand)); //$NON-NLS-1$ //$NON-NLS-2$
        if (addResult.exitCode != 0) {
            if (isSudoPasswordRequired(addResult.output)) {
                getReturnValue().getExecuteFailedMessages().add("rsyslog.conf 갱신을 위한 sudo 권한이 필요합니다."); //$NON-NLS-1$
            } else {
                getReturnValue().getExecuteFailedMessages().add("rsyslog.conf 갱신 실패 (종료 코드: " //$NON-NLS-1$
                        + addResult.exitCode + ")\n" + addResult.output); //$NON-NLS-1$
            }
            getReturnValue().setActionReturnValue(addResult.output);
            setSucceeded(false);
            return;
        }

        CommandResult restartResult = runCommand(Arrays.asList(
                SUDO_COMMAND, "-n", SYSTEMCTL_COMMAND, "restart", "rsyslog")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        getReturnValue().setActionReturnValue(combineOutput(addResult.output, restartResult.output));
        if (restartResult.exitCode == 0) {
            setSucceeded(true);
        } else {
            if (isSudoPasswordRequired(restartResult.output)) {
                getReturnValue().getExecuteFailedMessages().add("rsyslog 재시작을 위한 sudo 권한이 필요합니다."); //$NON-NLS-1$
            } else {
                getReturnValue().getExecuteFailedMessages().add("rsyslog 재시작 실패 (종료 코드: " //$NON-NLS-1$
                        + restartResult.exitCode + ")\n" + restartResult.output); //$NON-NLS-1$
            }
            setSucceeded(false);
        }
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        return Collections.singletonList(new PermissionSubject(Guid.SYSTEM, VdcObjectType.System,
                ActionGroup.AUDIT_LOG_MANAGEMENT));
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        return AuditLogType.UNASSIGNED;
    }

    private String escapeForSingleQuotes(String value) {
        return value.replace("'", "'\"'\"'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String combineOutput(String first, String second) {
        if (first == null || first.isEmpty()) {
            return second == null ? "" : second; //$NON-NLS-1$
        }
        if (second == null || second.isEmpty()) {
            return first;
        }
        return first + "\n" + second; //$NON-NLS-1$
    }

    private CommandResult runCommand(List<String> command) {
        StringBuilder output = new StringBuilder();
        int exitCode;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n"); //$NON-NLS-1$
                }
            }
            exitCode = process.waitFor();
        } catch (Exception e) {
            log.error("Failed to execute remote backup command", e);
            output.append("실행 중 오류: ").append(e.getMessage()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            exitCode = 1;
        }
        return new CommandResult(exitCode, output.toString().trim());
    }

    private boolean isSudoPasswordRequired(String output) {
        if (output == null) {
            return false;
        }
        String lowerOutput = output.toLowerCase();
        return lowerOutput.contains("password is required"); //$NON-NLS-1$
    }

    private static final class CommandResult {
        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}

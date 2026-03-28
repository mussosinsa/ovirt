package org.ovirt.engine.core.bll;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

public class RestoreAuditLogBackupCommand extends CommandBase<AuditLogBackupParameters> {

    private static final String AUDIT_LOG_DIR = "/var/log/ovirt-engine"; //$NON-NLS-1$

    public RestoreAuditLogBackupCommand(AuditLogBackupParameters parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    @Override
    protected void executeCommand() {
        String backupPath = getParameters().getBackupPath();
        String selectedBackupFile = getParameters().getSelectedBackupFile();

        if (backupPath == null || backupPath.trim().isEmpty()) {
            getReturnValue().getExecuteFailedMessages().add("저장 위치가 비어 있습니다."); //$NON-NLS-1$
            setSucceeded(false);
            return;
        }
        if (selectedBackupFile == null || selectedBackupFile.trim().isEmpty()) {
            getReturnValue().getExecuteFailedMessages().add("복구할 감사기록 파일을 선택해 주세요."); //$NON-NLS-1$
            setSucceeded(false);
            return;
        }

        Path directory = Paths.get(backupPath.trim()).normalize();
        Path restoreArchive = directory.resolve(selectedBackupFile).normalize();
        if (!restoreArchive.startsWith(directory) || !Files.isRegularFile(restoreArchive)) {
            getReturnValue().getExecuteFailedMessages().add("복구 파일을 찾을 수 없습니다: " + restoreArchive); //$NON-NLS-1$
            setSucceeded(false);
            return;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")); //$NON-NLS-1$
        Path currentBackup = directory.resolve("pre-restore-current-audit-" + timestamp + ".tar.gz"); //$NON-NLS-1$ //$NON-NLS-2$

        CommandResult currentBackupResult = runCommand(Arrays.asList(
                "tar", "-czf", currentBackup.toString(), AUDIT_LOG_DIR)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (currentBackupResult.exitCode != 0) {
            getReturnValue().getExecuteFailedMessages().add(
                    "현재 감사기록 백업 실패: " + currentBackupResult.output); //$NON-NLS-1$
            setSucceeded(false);
            return;
        }

        CommandResult restoreResult = runCommand(Arrays.asList(
                "tar", "-xzf", restoreArchive.toString(), "-C", "/")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (restoreResult.exitCode != 0) {
            getReturnValue().getExecuteFailedMessages().add("감사기록 복구 실패: " + restoreResult.output); //$NON-NLS-1$
            setSucceeded(false);
            return;
        }

        getReturnValue().setActionReturnValue(
                "현재 감사기록 백업: " + currentBackup + "\n복구 완료: " + restoreArchive); //$NON-NLS-1$ //$NON-NLS-2$
        setSucceeded(true);
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
            output.append("실행 중 오류: ").append(e.getMessage()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            exitCode = 1;
        }
        return new CommandResult(exitCode, output.toString().trim());
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

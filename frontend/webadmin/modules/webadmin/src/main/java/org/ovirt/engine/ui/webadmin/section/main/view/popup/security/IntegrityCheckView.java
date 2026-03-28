package org.ovirt.engine.ui.webadmin.section.main.view.popup.security;

import org.gwtbootstrap3.client.ui.Button;
import org.ovirt.engine.core.common.action.ActionParametersBase;
import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.ui.frontend.Frontend;
import org.ovirt.engine.ui.uicompat.FrontendActionAsyncResult;
import org.ovirt.engine.ui.webadmin.ApplicationConstants;
import org.ovirt.engine.ui.webadmin.gin.AssetProvider;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

public class IntegrityCheckView extends Composite {

    interface ViewUiBinder extends UiBinder<Widget, IntegrityCheckView> {
        ViewUiBinder uiBinder = GWT.create(ViewUiBinder.class);
    }

    private static final ApplicationConstants constants = AssetProvider.getConstants();

    @UiField
    Button securityAuditButton;

    @UiField
    Button integrityVerificationButton;

    @UiField
    Label securityAuditStatusLabel;

    @UiField
    Label integrityVerificationStatusLabel;

    @UiField
    HTML securityAuditErrorLabel;

    @UiField
    HTML integrityVerificationErrorLabel;

    public IntegrityCheckView() {
        initWidget(ViewUiBinder.uiBinder.createAndBindUi(this));
        initializeHandlers();
    }

    private void initializeHandlers() {
        securityAuditButton.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                setRunningState(
                        securityAuditButton,
                        securityAuditStatusLabel,
                        securityAuditErrorLabel
                );
                executeSecurityAudit();
            }
        });

        integrityVerificationButton.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                setRunningState(
                        integrityVerificationButton,
                        integrityVerificationStatusLabel,
                        integrityVerificationErrorLabel
                );
                executeIntegrityVerification();
            }
        });
    }

    private void setRunningState(Button button, Label statusLabel, HTML errorLabel) {
        button.setEnabled(false);
        statusLabel.setText(constants.statusRunning());
        resetStatusStyles(statusLabel);
        statusLabel.addStyleName("text-warning"); //$NON-NLS-1$
        errorLabel.setHTML(""); //$NON-NLS-1$
        errorLabel.setVisible(false);
    }

    private void setNormalState(Button button, Label statusLabel, HTML errorLabel) {
        button.setEnabled(true);
        statusLabel.setText(constants.statusNormal());
        resetStatusStyles(statusLabel);
        statusLabel.addStyleName("text-success"); //$NON-NLS-1$
        errorLabel.setHTML(""); //$NON-NLS-1$
        errorLabel.setVisible(false);
    }

    private void setFailedState(Button button, Label statusLabel, HTML errorLabel, FrontendActionAsyncResult result) {
        button.setEnabled(true);
        statusLabel.setText(constants.statusFailed());
        resetStatusStyles(statusLabel);
        statusLabel.addStyleName("text-danger"); //$NON-NLS-1$
        showErrorDetails(result, errorLabel);
    }

    private void resetStatusStyles(Label statusLabel) {
        statusLabel.removeStyleName("text-success"); //$NON-NLS-1$
        statusLabel.removeStyleName("text-danger"); //$NON-NLS-1$
        statusLabel.removeStyleName("text-warning"); //$NON-NLS-1$
    }

    private void executeSecurityAudit() {
        Frontend.getInstance().runAction(
            ActionType.SecurityAudit,
            new ActionParametersBase(),
            result -> {
                if (result != null && result.getReturnValue() != null && result.getReturnValue().getSucceeded()) {
                    setNormalState(
                            securityAuditButton,
                            securityAuditStatusLabel,
                            securityAuditErrorLabel
                    );
                } else {
                    setFailedState(
                            securityAuditButton,
                            securityAuditStatusLabel,
                            securityAuditErrorLabel,
                            result
                    );
                }
            }
        );
    }

    private void executeIntegrityVerification() {
        Frontend.getInstance().runAction(
            ActionType.IntegrityVerification,
            new ActionParametersBase(),
            result -> {
                if (result != null && result.getReturnValue() != null && result.getReturnValue().getSucceeded()) {
                    setNormalState(
                            integrityVerificationButton,
                            integrityVerificationStatusLabel,
                            integrityVerificationErrorLabel
                    );
                } else {
                    setFailedState(
                            integrityVerificationButton,
                            integrityVerificationStatusLabel,
                            integrityVerificationErrorLabel,
                            result
                    );
                }
            }
        );
    }

    private void showErrorDetails(FrontendActionAsyncResult result, HTML errorLabel) {
        StringBuilder errorMsg = new StringBuilder();

        if (result != null && result.getReturnValue() != null) {
            if (result.getReturnValue().getExecuteFailedMessages() != null
                    && !result.getReturnValue().getExecuteFailedMessages().isEmpty()) {
                for (String msg : result.getReturnValue().getExecuteFailedMessages()) {
                    if (msg != null && !msg.trim().isEmpty()) {
                        errorMsg.append(msg).append("\n"); //$NON-NLS-1$
                    }
                }
            }

            Object actionReturnValue = result.getReturnValue().getActionReturnValue();
            if (actionReturnValue instanceof String) {
                String output = ((String) actionReturnValue).trim();
                if (!output.isEmpty()) {
                    if (errorMsg.length() > 0) {
                        errorMsg.append("\n"); //$NON-NLS-1$
                    }
                    errorMsg.append(output);
                }
            }
        }

        if (errorMsg.length() == 0) {
            errorMsg.append("오류가 발생했습니다. 다시 실행해 주세요."); //$NON-NLS-1$
        }

        String htmlContent = SafeHtmlUtils.fromString(errorMsg.toString().trim())
                .asString()
                .replace("\n", "<br/>"); //$NON-NLS-1$ //$NON-NLS-2$
        errorLabel.setHTML(htmlContent);
        errorLabel.setVisible(true);
    }
}

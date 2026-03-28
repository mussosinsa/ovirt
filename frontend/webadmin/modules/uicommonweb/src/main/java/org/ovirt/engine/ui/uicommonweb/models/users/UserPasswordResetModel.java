package org.ovirt.engine.ui.uicommonweb.models.users;

import org.ovirt.engine.ui.uicommonweb.models.EntityModel;
import org.ovirt.engine.ui.uicommonweb.models.Model;
import org.ovirt.engine.ui.uicommonweb.validation.IValidation;
import org.ovirt.engine.ui.uicommonweb.validation.NotEmptyValidation;
import org.ovirt.engine.ui.uicompat.ConstantsManager;

public class UserPasswordResetModel extends Model {

    private EntityModel<String> password;

    public EntityModel<String> getPassword() {
        return password;
    }

    private void setPassword(EntityModel<String> value) {
        password = value;
    }

    public UserPasswordResetModel() {
        setPassword(new EntityModel<String>());
        setTitle(ConstantsManager.getInstance().getConstants().resetPasswordTitle());
        setHashName("reset_password"); //$NON-NLS-1$
    }

    public boolean validate() {
        getPassword().validateEntity(new IValidation[] { new NotEmptyValidation() });
        return getPassword().getIsValid();
    }
}

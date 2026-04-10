package org.ovirt.engine.core.common;

import java.util.EnumSet;
import java.util.Set;

import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.core.common.businessentities.Managed;

/**
 * Defines which engine actions are permitted on LXC containers.
 * LXC containers are unmanaged (isManaged() == false) and share the
 * host kernel, so operations requiring full VM lifecycle (snapshots,
 * live migration, disk hot-plug, etc.) are not supported.
 */
public final class LxcSupportedActions {

    private static final Set<ActionType> SUPPORTED_ACTIONS = EnumSet.of(
            ActionType.AddVm,
            ActionType.AddVmFromScratch,
            ActionType.AddUnmanagedVms,
            ActionType.RemoveVm,
            ActionType.RunVm,
            ActionType.StopVm,
            ActionType.ShutdownVm,
            ActionType.RebootVm,
            ActionType.VmLogon,
            ActionType.MigrateVm,
            ActionType.MigrateMultipleVms,
            ActionType.AddEventSubscription,
            ActionType.RemoveEventSubscription,
            ActionType.AddExternalEvent,
            ActionType.AddProvider,
            ActionType.UpdateProvider,
            ActionType.RemoveProvider,
            ActionType.TestProviderConnectivity
    );

    private LxcSupportedActions() {
        // utility class
    }

    public static boolean isActionSupported(Managed entity, ActionType actionType) {
        if (entity.isManaged()) {
            return true;
        }
        return SUPPORTED_ACTIONS.contains(actionType);
    }
}

package org.ovirt.engine.core.vdsbroker.monitoring;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.dao.ClusterDao;
import org.ovirt.engine.core.dao.ImageTransferDao;
import org.ovirt.engine.core.dao.VdsDao;
import org.ovirt.engine.core.dao.VmDao;
import org.ovirt.engine.core.dao.VmDynamicDao;

/**
 * Monitoring strategy for LXC (Linux Container) nodes.
 * LXC containers share the host kernel and do not use KVM or hardware
 * emulation, so the KVM-enabled and emulated-machine checks performed by
 * {@link VirtMonitoringStrategy} are skipped here.
 */
@Singleton
public class LxcMonitoringStrategy extends VirtMonitoringStrategy {

    @Inject
    public LxcMonitoringStrategy(ClusterDao clusterDao,
            VdsDao vdsDao,
            VmDao vmDao,
            VmDynamicDao vmDynamicDao,
            ImageTransferDao imageTransferDao) {
        super(clusterDao, vdsDao, vmDao, vmDynamicDao, imageTransferDao);
    }

    /**
     * LXC nodes do not require KVM or a specific emulated machine type.
     * No non-operational state is triggered for those checks.
     */
    @Override
    public void processSoftwareCapabilities(VDS vds) {
        // Skip KVM-enabled check and emulated-machine check intentionally.
        // LXC containers run natively on the host kernel without hardware virtualisation.
    }

    /**
     * LXC nodes do not expose CPU flags via VDSM capabilities, so there is
     * nothing to process here.
     */
    @Override
    public void processHardwareCapabilities(VDS vds) {
        // no-op for LXC
    }

    @Override
    public boolean processHardwareCapabilitiesNeeded(VDS oldVds, VDS newVds) {
        return false;
    }

    /** LXC nodes are managed via VDSM — no hardware power-management fencing. */
    @Override
    public boolean isPowerManagementSupported() {
        return false;
    }
}

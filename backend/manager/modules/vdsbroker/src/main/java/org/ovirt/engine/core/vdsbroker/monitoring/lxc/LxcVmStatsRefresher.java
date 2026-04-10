package org.ovirt.engine.core.vdsbroker.monitoring.lxc;

import java.util.List;
import java.util.stream.Stream;

import org.ovirt.engine.core.common.businessentities.VmDynamic;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.vdsbroker.ResourceManager;
import org.ovirt.engine.core.vdsbroker.VdsManager;
import org.ovirt.engine.core.vdsbroker.monitoring.PollVmStatsRefresher;
import org.ovirt.engine.core.vdsbroker.monitoring.VdsmVm;

/**
 * VM stats refresher for LXC (Linux Container) nodes.
 * LXC containers are reported via standard VDSM getAllVmStats, so this
 * refresher reuses the base VDSM polling mechanism.
 *
 * Device monitoring is skipped because LXC containers do not expose
 * the same virtual hardware devices as full KVM VMs.
 */
public class LxcVmStatsRefresher extends PollVmStatsRefresher {

    public LxcVmStatsRefresher(VdsManager vdsManager, ResourceManager resourceManager) {
        super(vdsManager);
    }

    @Override
    protected long getRefreshRate() {
        return VMS_REFRESH_RATE;
    }

    /**
     * LXC containers do not have virtual hardware devices (disks, NICs attached
     * as QEMU devices), so skip per-VM device monitoring entirely.
     */
    @Override
    protected Stream<VdsmVm> filterVmsToDevicesMonitoring(List<Pair<VmDynamic, VdsmVm>> polledVms) {
        return Stream.empty();
    }
}

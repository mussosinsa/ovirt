package org.ovirt.engine.core.vdsbroker.monitoring.lxc;

import org.ovirt.engine.core.vdsbroker.ResourceManager;
import org.ovirt.engine.core.vdsbroker.VdsManager;
import org.ovirt.engine.core.vdsbroker.monitoring.HostConnectionRefresher;
import org.ovirt.engine.core.vdsbroker.monitoring.HostConnectionRefresherInterface;

/**
 * Host connection refresher for LXC (Linux Container) nodes.
 * LXC containers are managed via libvirt/VDSM on the host, so the
 * standard VDSM JSON-RPC event subscription is sufficient.
 */
public class LxcHostConnectionRefresher implements HostConnectionRefresherInterface {

    private final HostConnectionRefresher delegate;

    public LxcHostConnectionRefresher(VdsManager vdsManager, ResourceManager resourceManager) {
        this.delegate = new HostConnectionRefresher(vdsManager, resourceManager);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }
}

#
# Copyright oVirt Authors
# SPDX-License-Identifier: Apache-2.0
#


DEV_PYTHON_DIR = '/usr/share/ovirt-engine/usr/lib/python3.9/site-packages'
ENGINE_VARS = '/usr/share/ovirt-engine/etc/ovirt-engine/engine.conf'
ENGINE_FKLSNR_VARS = '/usr/share/ovirt-engine/etc/ovirt-engine/ovirt-fence-kdump-listener.conf'


import sys

if DEV_PYTHON_DIR:
    sys.path.append(DEV_PYTHON_DIR)


# vim: expandtab tabstop=4 shiftwidth=4

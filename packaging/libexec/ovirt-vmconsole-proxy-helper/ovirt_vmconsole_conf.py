#
# Copyright oVirt Authors
# SPDX-License-Identifier: Apache-2.0
#


DEV_PYTHON_DIR = '/usr/share/ovirt-engine/usr/lib/python3.9/site-packages'
VMCONSOLE_PROXY_HELPER_VARS = '/usr/share/ovirt-engine/etc/ovirt-engine/ovirt-vmconsole-proxy-helper.conf'
VMCONSOLE_PROXY_HELPER_DEFAULTS = '/usr/share/ovirt-engine/share/ovirt-engine/conf/ovirt-vmconsole-proxy-helper.conf'


import sys

if DEV_PYTHON_DIR:
    sys.path.append(DEV_PYTHON_DIR)


# vim: expandtab tabstop=4 shiftwidth=4

#
# Copyright oVirt Authors
# SPDX-License-Identifier: Apache-2.0
#


DEV_PYTHON_DIR = '/usr/share/ovirt-engine/usr/lib/python3.9/site-packages'
ENGINE_DEFAULTS = '/usr/share/ovirt-engine/share/ovirt-engine/services/ovirt-engine/ovirt-engine.conf'
ENGINE_VARS = '/usr/share/ovirt-engine/etc/ovirt-engine/engine.conf'
ENGINE_NOTIFIER_VARS = '/usr/share/ovirt-engine/etc/ovirt-engine/notifier/notifier.conf'


import sys

if DEV_PYTHON_DIR:
    sys.path.insert(0, DEV_PYTHON_DIR)


# vim: expandtab tabstop=4 shiftwidth=4

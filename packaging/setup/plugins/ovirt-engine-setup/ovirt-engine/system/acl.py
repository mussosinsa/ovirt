#
# ovirt-engine-setup -- ovirt engine setup
#
# Copyright oVirt Authors
# SPDX-License-Identifier: Apache-2.0
#
#


"""Engine ACL and sudoers adjustments."""


import gettext
import os

from otopi import plugin
from otopi import util

from ovirt_engine_setup import constants as osetupcons
from ovirt_engine_setup.engine import constants as oenginecons


def _(m):
    return gettext.dgettext(message=m, domain='ovirt-engine-setup')


@util.export
class Plugin(plugin.PluginBase):
    """Engine ACL and sudoers adjustments plugin."""

    def __init__(self, context):
        super(Plugin, self).__init__(context=context)

    @plugin.event(
        stage=plugin.Stages.STAGE_INIT,
    )
    def _init(self):
        self.command.detect('setfacl')

    @plugin.event(
        stage=plugin.Stages.STAGE_CLOSEUP,
        condition=lambda self: (
            self.environment[oenginecons.CoreEnv.ENABLE] and
            not self.environment[
                osetupcons.CoreEnv.DEVELOPER_MODE
            ]
        ),
    )
    def _closeup(self):
        sudoers_path = '/etc/sudoers.d/ovirt-aide'
        sudoers_content = (
            'ovirt ALL=(root) NOPASSWD: /usr/sbin/aide --check\n'
        )
        with open(sudoers_path, 'w', encoding='utf-8') as sudoers_file:
            sudoers_file.write(sudoers_content)
        os.chmod(sudoers_path, 0o440)

        engine_proxy_conf = oenginecons.FileLocations.HTTPD_CONF_OVIRT_ENGINE
        session_limit_conf = os.path.join(
            oenginecons.FileLocations.OVIRT_ENGINE_SYSCONFDIR,
            'engine.conf.d',
            '99-limit-user-sessions.conf',
        )
        aide_conf = '/etc/aide.conf'

        self._set_acl_if_exists(engine_proxy_conf, 'rw')
        self._set_acl_if_exists(session_limit_conf, 'rw')
        self._set_acl_if_exists(aide_conf, 'r')

    def _set_acl_if_exists(self, path, permissions):
        if not os.path.exists(path):
            self.logger.info(
                _('Skipping ACL update; file is missing: %s'),
                path,
            )
            return
        self.execute(
            args=[
                self.command.get('setfacl'),
                '-m',
                'u:ovirt:{permissions}'.format(
                    permissions=permissions,
                ),
                path,
            ],
        )


# vim: expandtab tabstop=4 shiftwidth=4

/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.unwrapIDN

class SSHSettingsActivity : ProfileSettingsActivity<SSHBean>() {

    override fun createEntity() = SSHBean()

    override fun SSHBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverAuthType = authType
        DataStore.serverPassword = password
        DataStore.serverPrivateKey = privateKey
        DataStore.serverPassword1 = privateKeyPassphrase
        DataStore.serverCertificates = publicKey
        DataStore.serverSSHKeepaliveInterval = keepaliveInterval
        DataStore.serverUdpgwEnabled = udpgwEnabled
        DataStore.serverUdpgwAddress = udpgwAddress
        DataStore.serverUdpgwPort = udpgwPort
        DataStore.serverUdpgwMaxConnections = udpgwMaxConnections
        DataStore.serverSSHConnectionCount = connectionCount
        DataStore.serverSSHClientVersion = clientVersion
        DataStore.serverSSHMtuMode = mtuMode
        DataStore.serverSSHMtu = mtu
    }

    override fun SSHBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        authType = DataStore.serverAuthType
        when (authType) {
            SSHBean.AUTH_TYPE_NONE -> {
            }
            SSHBean.AUTH_TYPE_PASSWORD -> {
                password = DataStore.serverPassword
            }
            SSHBean.AUTH_TYPE_PUBLIC_KEY -> {
                privateKey = DataStore.serverPrivateKey
                privateKeyPassphrase = DataStore.serverPassword1
            }
        }
        publicKey = DataStore.serverCertificates
        keepaliveInterval = DataStore.serverSSHKeepaliveInterval
        udpgwEnabled = DataStore.serverUdpgwEnabled
        udpgwAddress = DataStore.serverUdpgwAddress
        udpgwPort = DataStore.serverUdpgwPort
        udpgwMaxConnections = DataStore.serverUdpgwMaxConnections
        connectionCount = DataStore.serverSSHConnectionCount.coerceIn(1, 10)
        clientVersion = DataStore.serverSSHClientVersion
        mtuMode = DataStore.serverSSHMtuMode
        mtu = DataStore.serverSSHMtu.coerceIn(576, 9000)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.ssh_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        val password = findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        val privateKey = findPreference<EditTextPreference>(Key.SERVER_PRIVATE_KEY)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        val privateKeyPassphrase = findPreference<EditTextPreference>(Key.SERVER_PASSWORD1)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_SSH_KEEPALIVE_INTERVAL)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_SSH_CONNECTION_COUNT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_SSH_CLIENT_VERSION)!!.apply {
            // An SSH identification string that does not start with this is rejected before the
            // handshake even begins, so reject it here rather than at connect time.
            setOnPreferenceChangeListener { _, newValue ->
                val text = (newValue as String).trim()
                val valid = text.isEmpty() || text.startsWith("SSH-2.0-")
                if (!valid) {
                    Toast.makeText(app, R.string.ssh_client_version_invalid, Toast.LENGTH_LONG).show()
                }
                valid
            }
        }
        val mtu = findPreference<EditTextPreference>(Key.SERVER_SSH_MTU)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        val mtuMode = findPreference<ListPreference>(Key.SERVER_SSH_MTU_MODE)!!
        fun updateMtuMode(mode: Int = DataStore.serverSSHMtuMode) {
            mtu.isVisible = mode == SSHBean.MTU_MODE_MANUAL
        }
        updateMtuMode()
        mtuMode.setOnPreferenceChangeListener { _, newValue ->
            updateMtuMode((newValue as String).toInt())
            true
        }
        findPreference<EditTextPreference>(Key.SERVER_UDPGW_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_UDPGW_MAX_CONNECTIONS)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        val udpgwAddress = findPreference<EditTextPreference>(Key.SERVER_UDPGW_ADDRESS)!!
        val udpgwPort = findPreference<EditTextPreference>(Key.SERVER_UDPGW_PORT)!!
        val udpgwMaxConnections = findPreference<EditTextPreference>(Key.SERVER_UDPGW_MAX_CONNECTIONS)!!
        val udpgwEnabled = findPreference<SwitchPreference>(Key.SERVER_UDPGW_ENABLED)!!
        fun updateUdpgw(enabled: Boolean = DataStore.serverUdpgwEnabled) {
            udpgwAddress.isVisible = enabled
            udpgwPort.isVisible = enabled
            udpgwMaxConnections.isVisible = enabled
        }
        updateUdpgw()
        udpgwEnabled.setOnPreferenceChangeListener { _, newValue ->
            updateUdpgw(newValue as Boolean)
            true
        }

        val authType = findPreference<ListPreference>(Key.SERVER_AUTH_TYPE)!!
        fun updateAuthType(type: Int = DataStore.serverAuthType) {
            password.isVisible = type == SSHBean.AUTH_TYPE_PASSWORD
            privateKey.isVisible = type == SSHBean.AUTH_TYPE_PUBLIC_KEY
            privateKeyPassphrase.isVisible = type == SSHBean.AUTH_TYPE_PUBLIC_KEY
        }
        updateAuthType()
        authType.setOnPreferenceChangeListener { _, newValue ->
            updateAuthType((newValue as String).toInt())
            true
        }
    }

}
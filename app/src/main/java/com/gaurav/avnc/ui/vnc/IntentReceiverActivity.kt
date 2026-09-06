/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.gaurav.avnc.ui.vnc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mcai.ubuntudsu.R
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.vnc.VncUri
import kotlinx.coroutines.launch

/**
 * Handles "external" intents and launches [VncActivity] with appropriate profiles.
 *
 * Current intent types:
 *  - vnc:// URIs
 *  - App shortcuts
 */
class IntentReceiverActivity : AppCompatActivity() {

    companion object {
        fun createShortcutIntent(context: Context, profileId: Long): Intent =
                Intent(context, IntentReceiverActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent()
    }

    private fun handleIntent() = lifecycleScope.launch {
        if (intent.data?.scheme == "vnc")
            handleVncUri(VncUri(intent.data!!.toString()))
        else
            toast("Invalid intent: Server info is missing!")

        finish()
    }

    private suspend fun handleVncUri(uri: VncUri) {
        if (!validateUri(uri))
            return

        launchFromVncUri(uri)
    }

    private suspend fun launchFromVncUri(uri: VncUri) {
        if (!uri.host.isNullOrBlank())
            startVncActivity(this, uri)
        else if (!uri.connectionName.isNullOrBlank())
            toast("No server found with name '${uri.connectionName}'")
        else
            toast(getString(R.string.msg_invalid_vnc_uri))
    }

    private fun validateUri(uri: VncUri): Boolean {
        if (uri.host.isNullOrBlank() && uri.connectionName.isNullOrBlank()) {
            toast(getString(R.string.msg_invalid_vnc_uri))
            return false
        }

        if (uri.host.isNullOrBlank() && uri.connectionName.isNullOrBlank()) {
            toast(getString(R.string.msg_invalid_vnc_uri))
            return false
        }

        if (uri.channelType !in listOf(null, ServerProfile.CHANNEL_TCP, ServerProfile.CHANNEL_SSH_TUNNEL)) {
            toast("Unknown channel type: ${uri.channelType}")
            return false
        }

        val supportedSecurityTypes = resources.getStringArray(R.array.profile_editor_security_values)
        if (uri.securityType != null && uri.securityType.toString() !in supportedSecurityTypes) {
            toast("Unknown security type: ${uri.securityType}")
            return false
        }

        if (uri.channelType == ServerProfile.CHANNEL_SSH_TUNNEL && uri.sshHost.isNullOrBlank()) {
            toast("Missing SSH host in URI")
            return false
        }

        if (uri.channelType == ServerProfile.CHANNEL_SSH_TUNNEL && uri.sshUsername.isNullOrBlank()) {
            toast("Missing SSH username in URI")
            return false
        }

        return true
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

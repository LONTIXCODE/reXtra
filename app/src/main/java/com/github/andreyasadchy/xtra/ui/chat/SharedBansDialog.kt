package com.github.andreyasadchy.xtra.ui.chat

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.SharedBansRepository
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

internal class SharedBansDialog : DialogFragment() {
    private var request: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val userId = requireNotNull(args.getString(USER_ID))
        val profiles = (requireContext().applicationContext as XtraApp).xtraModule.chatterinoPlusProfiles
        val dialog = requireContext().getAlertDialogBuilder()
            .setTitle(getString(R.string.chat_plus_shared_bans_for, args.getString(NAME) ?: userId))
            .setMessage(R.string.chat_plus_sign_in_info)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.chat_plus_sign_in, null)
            .create()

        fun load(signIn: Boolean) {
            if (request?.isActive == true) return
            val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            action.isEnabled = false
            dialog.setMessage(getString(if (signIn) R.string.chat_plus_sign_in_wait else R.string.chat_plus_bans_loading))
            request = lifecycleScope.launch {
                var message: String
                try {
                    if (signIn) profiles.signIn { url -> startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                    val bans = profiles.lookup(userId, args.getString(CHANNEL), args.getString(LOGIN))
                    parentFragmentManager.setFragmentResult(RESULT, Bundle().apply {
                        putString(USER_ID, userId)
                        putInt(COUNT, bans.count { !it.lifted })
                    })
                    message = if (bans.isEmpty()) getString(R.string.chat_plus_bans_empty) else bans.joinToString("\n\n") { ban ->
                        val detail = getString(if (ban.lifted) R.string.chat_plus_ban_lifted else R.string.chat_plus_ban_active)
                        val date = if (ban.since > 0) TwitchApiHelper.formatDate(requireContext(), ban.since * 1_000) else null
                        listOfNotNull(ban.channel, detail, date).joinToString(" · ")
                    }
                    action.isVisible = false
                } catch (e: SharedBansRepository.Failure) {
                    val label = when (e.problem) {
                        SharedBansRepository.Problem.ACCOUNT -> R.string.chat_plus_twitch_required
                        SharedBansRepository.Problem.SIGN_IN -> R.string.chat_plus_sign_in_info
                        SharedBansRepository.Problem.DENIED -> R.string.chat_plus_account_mismatch
                        SharedBansRepository.Problem.EXPIRED -> R.string.chat_plus_sign_in_expired
                        SharedBansRepository.Problem.LIMIT -> R.string.chat_plus_bans_limit
                        SharedBansRepository.Problem.CONNECTION -> R.string.chat_plus_connection
                    }
                    message = getString(label)
                } catch (_: TimeoutCancellationException) {
                    message = getString(R.string.chat_plus_connection)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message = getString(R.string.chat_plus_connection)
                }
                dialog.setMessage(message)
                action.setText(if (profiles.signedIn()) R.string.chat_banned_retry else R.string.chat_plus_sign_in)
                action.isEnabled = true
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { load(!profiles.signedIn()) }
            if (profiles.signedIn()) load(false)
        }
        return dialog
    }

    override fun onDestroyView() {
        request?.cancel()
        super.onDestroyView()
    }

    companion object {
        const val RESULT = "shared_bans_result"
        const val USER_ID = "user_id"
        const val COUNT = "count"
        private const val NAME = "name"
        private const val CHANNEL = "channel"
        private const val LOGIN = "login"

        fun newInstance(userId: String, name: String?, channelId: String?, channelLogin: String?) =
            SharedBansDialog().apply {
                arguments = Bundle().apply {
                    putString(USER_ID, userId)
                    putString(NAME, name)
                    putString(CHANNEL, channelId)
                    putString(LOGIN, channelLogin)
                }
            }
    }
}

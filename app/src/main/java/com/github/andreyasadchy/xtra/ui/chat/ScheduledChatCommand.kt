package com.github.andreyasadchy.xtra.ui.chat

internal sealed interface ScheduledChatCommand {
    data object Stop : ScheduledChatCommand
    data class Send(val intervalSeconds: Long, val count: Int?, val message: String) : ScheduledChatCommand

    companion object {
        private val separator = Regex("[ \\t]+")

        fun parse(command: String): ScheduledChatCommand? {
            val parts = command.trim().split(separator, limit = 2)
            if (!parts[0].equals("/msg", ignoreCase = true)) return null
            val input = parts.getOrNull(1)?.trim() ?: return null
            if (input.equals("stop", ignoreCase = true)) return Stop
            val arguments = input.split(separator, limit = 3)
            val interval: Long
            val count: Int?
            val message: String
            if (arguments[0].startsWith('x', ignoreCase = true)) {
                count = positive(arguments[0].drop(1)) ?: return null
                interval = positive(arguments.getOrNull(1))?.toLong() ?: return null
                message = arguments.getOrNull(2) ?: return null
            } else {
                count = null
                interval = (positive(arguments[0]) ?: return null) * 60L
                message = input.split(separator, limit = 2).getOrNull(1) ?: return null
            }
            return Send(interval, count, message.trim()).takeIf { validMessage(it.message) }
        }

        fun validMessage(message: String) = message.isNotBlank() && message.length <= 500 &&
            !message.startsWith("/") && '\r' !in message && '\n' !in message

        private fun positive(value: String?): Int? = value?.takeIf { it.all { digit -> digit in '0'..'9' } }
            ?.toIntOrNull()?.takeIf { it > 0 }
    }
}

package com.example.voicechanger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallReceiver : BroadcastReceiver() {
    companion object {
        private var wasOffhook = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (stateStr == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                wasOffhook = true
            } else if (stateStr == TelephonyManager.EXTRA_STATE_IDLE) {
                if (wasOffhook) {
                    wasOffhook = false
                    val prefs = context.getSharedPreferences("voice_changer_settings", Context.MODE_PRIVATE)
                    val autoLaunch = prefs.getBoolean("auto_launch_on_call_end", false)
                    if (autoLaunch) {
                        LogManager.i(context, "CALL", "Phone call ended! Auto-launching VoiceChanger...")
                        val launchIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("AUTO_SELECT_LATEST_CALL", true)
                        }
                        context.startActivity(launchIntent)
                    }
                }
            }
        }
    }
}

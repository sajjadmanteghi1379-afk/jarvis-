package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class JarvisServiceRestarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.e("JARVIS_SERVICE", "Restarting JarvisListenerService")
        val serviceIntent = Intent(context, JarvisListenerService::class.java)
        context.startForegroundService(serviceIntent)
    }
}

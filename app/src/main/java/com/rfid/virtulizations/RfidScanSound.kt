package com.rfid.virtulizations

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock

/** Uses the identical scan_buzzer audio bundled with the supplied SDK. */
class RfidScanSound(private val context: Context) {
    private var pool: SoundPool? = null
    private var sound = 0
    private var ready = false
    private var lastBeep = Long.MIN_VALUE
    @Synchronized fun start() {
        if (pool != null) return
        val created = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = created
        created.setOnLoadCompleteListener { loadedPool, _, status ->
            synchronized(this) { if (pool === loadedPool) ready = status == 0 }
        }
        sound = created.load(context, R.raw.rfid_scan_beep, 1)
        lastBeep = Long.MIN_VALUE
    }
    @Synchronized fun beep() {
        val active = pool ?: return
        if (!ready) return
        val now = SystemClock.elapsedRealtime()
        // Avoid overlapping sounds at high read rates; all events are still logged.
        if (lastBeep != Long.MIN_VALUE && now - lastBeep < 100) return
        active.play(sound, 1f, 1f, 1, 0, 1f)
        lastBeep = now
    }
    @Synchronized fun stop() {
        pool?.release()
        pool = null
        ready = false
        sound = 0
    }
}

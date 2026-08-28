package xyz.luan.audioplayers.player

import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import xyz.luan.audioplayers.AudioContextAndroid
import xyz.luan.audioplayers.source.Source

class MediaPlayerWrapper(
    private val wrappedPlayer: WrappedPlayer,
) : PlayerWrapper {
    private val mediaPlayer = createMediaPlayer(wrappedPlayer)

    /**
     * The speed last written to the native PlaybackParams. A rate set while
     * the player is paused only reaches the Dart-side field, so [start] uses
     * this to know when the native params must be re-synced.
     */
    private var appliedRate = 1.0f

    private fun createMediaPlayer(wrappedPlayer: WrappedPlayer): MediaPlayer {
        val mediaPlayer = MediaPlayer().apply {
            setOnPreparedListener { wrappedPlayer.onPrepared() }
            setOnCompletionListener { wrappedPlayer.onCompletion() }
            setOnSeekCompleteListener { wrappedPlayer.onSeekComplete() }
            setOnErrorListener { _, what, extra -> wrappedPlayer.onError(what, extra) }
            setOnBufferingUpdateListener { _, percent -> wrappedPlayer.onBuffering(percent) }
        }
        wrappedPlayer.context.setAttributesOnPlayer(mediaPlayer)
        return mediaPlayer
    }

    override fun getDuration(): Int? {
        // media player returns -1 if the duration is unknown
        return mediaPlayer.duration.takeUnless { it == -1 }
    }

    override fun getCurrentPosition(): Int {
        return mediaPlayer.currentPosition
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        mediaPlayer.setVolume(leftVolume, rightVolume)
    }

    override fun setRate(rate: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(rate)
            appliedRate = rate
        } else if (rate != 1.0f) {
            error("Changing the playback rate is only available for Android M/23+ or using LOW_LATENCY mode.")
        }
    }

    override fun setSource(source: Source) {
        reset()
        // reset() drops the native player back to default audio attributes
        // (USAGE_MEDIA). Reapply the configured context so a second source,
        // or a context updated on a live player, keeps the stream the app
        // chose (alarm, notification, …) instead of silently reverting to
        // the media stream.
        wrappedPlayer.context.setAttributesOnPlayer(mediaPlayer)
        source.setForMediaPlayer(mediaPlayer)
    }

    override fun setLooping(looping: Boolean) {
        mediaPlayer.isLooping = looping
    }

    override fun start() {
        // Explicitly start the player. The previous implementation started
        // playback only as a side effect of setting PlaybackParams (setRate),
        // which several OEM media frameworks do not implement: the call
        // succeeds, nothing plays, and no error or event is ever emitted
        // (observed on POS/TV boxes and reported for various handsets).
        // start() is the documented way to enter the Started state, and is a
        // no-op if PlaybackParams already started playback on stock Android.
        val rate = wrappedPlayer.rate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (rate != 1.0f || appliedRate != 1.0f) {
                // Re-sync the native params first: a rate changed while paused
                // is only stored Dart-side, and the old start-by-setting-params
                // behavior implicitly performed this sync on every start.
                mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(rate)
                appliedRate = rate
            }
        }
        mediaPlayer.start()
    }

    override fun pause() {
        mediaPlayer.pause()
    }

    override fun stop() {
        mediaPlayer.stop()
    }

    override fun release() {
        mediaPlayer.reset()
        mediaPlayer.release()
    }

    override fun seekTo(position: Int) {
        mediaPlayer.seekTo(position)
    }

    override fun updateContext(context: AudioContextAndroid) {
        context.setAttributesOnPlayer(mediaPlayer)
        if (context.stayAwake) {
            mediaPlayer.setWakeMode(wrappedPlayer.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        }
    }

    override fun prepare() {
        mediaPlayer.prepareAsync()
    }

    override fun reset() {
        mediaPlayer.reset()
        // reset() reverts the native player, including its PlaybackParams.
        appliedRate = 1.0f
    }

    override fun isLiveStream(): Boolean {
        val duration = getDuration()
        return duration == null || duration == 0
    }
}

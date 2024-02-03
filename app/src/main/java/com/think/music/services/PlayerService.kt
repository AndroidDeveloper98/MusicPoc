package com.think.music.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.media.app.NotificationCompat
import com.think.music.MainActivity
import com.think.music.R
import com.think.music.receivers.NotificationActionBroadcastReceiver
import com.think.music.util.Constants.CURRENT_SONG_DURATION_KEY
import com.think.music.util.Constants.NOTIFICATION_CHANNEL_ID
import com.think.music.util.Constants.NOTIFICATION_CHANNEL_NAME
import com.think.music.util.Constants.NOTIFICATION_ID
import com.think.music.util.Constants.POSITION_KEY
import com.think.music.util.Constants.PREF_NAME
import com.think.music.util.PlayPauseStateNotifier
import com.think.music.util.PlayerHelper.getSongThumbnail
import com.think.music.util.SeekCompletionNotifier
import com.think.music.util.SharedPreferenceUtil
import com.think.music.util.SongChangeNotifier
import java.io.IOException

// Actions for notification action buttons
const val ACTION_PREVIOUS = "action previous"
const val ACTION_PLAY_PAUSE = "action play pause"
const val ACTION_NEXT = "action next"
const val ACTION_MAIN = "action main"

@Suppress("DEPRECATION")
class PlayerService :
    Service(),
    MediaPlayer.OnCompletionListener,
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnSeekCompleteListener,
    MediaPlayer.OnErrorListener,
    AudioManager.OnAudioFocusChangeListener {
	
    companion object {
        private const val MEDIA_SESSION_ACTIONS = (
            PlaybackStateCompat.ACTION_PLAY
                or PlaybackStateCompat.ACTION_PAUSE
                or PlaybackStateCompat.ACTION_PLAY_PAUSE
                or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                or PlaybackStateCompat.ACTION_STOP
                or PlaybackStateCompat.ACTION_SEEK_TO
            )
    }
	
    private val TAG = "My" + this::class.java.simpleName
	
    private lateinit var currentSongChangeNotifier: SongChangeNotifier
    private lateinit var playPauseStateNotifier: PlayPauseStateNotifier
    private lateinit var seekCompleteNotifier: SeekCompletionNotifier
    private lateinit var audioManager: AudioManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var mediaSessionCompat: MediaSessionCompat
	
    private lateinit var focusRequest: AudioFocusRequest
	
    private var focus: Int? = null
    private var position = -1
    private val iBinder: IBinder = LocalBinder()
	
    var mediaPlayer: MediaPlayer? = null
	
    private fun iLog(m: String) = Log.i(TAG, m)
	
    override fun onBind(p0: Intent?): IBinder? {
        return iBinder
    }
	
    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
		
        mediaSessionCompat = MediaSessionCompat(this, "Music")
        mediaSessionCompat.setCallback(MediaSessionCallback(applicationContext, this))
        mediaSessionCompat.isActive = true
    }
	
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action != null) {
            when (intent.action) {
                ACTION_PREVIOUS -> {
                    playPrevious()
                }
				
                ACTION_PLAY_PAUSE -> {
                    playPause()
                    restartNotification()
                    if (!isPlaying()) stopForeground(false)
                }
				
                ACTION_NEXT -> {
                    playNext()
                }
				
                else -> Unit
            }
        }
        return START_NOT_STICKY
    }
	
    override fun onCompletion(mp: MediaPlayer?) {
        playNext()
    }
	
    override fun onPrepared(mp: MediaPlayer?) {
    }
	
    override fun onError(p0: MediaPlayer?, p1: Int, p2: Int): Boolean {
        mediaPlayer?.stop()
        Toast.makeText(this, "Invalid format or song", Toast.LENGTH_SHORT).show()
        with(sharedPreferences.edit()) {
            putInt(POSITION_KEY, position)
            apply()
        }
        stopForeground(false)
        return false
    }
	
    override fun onSeekComplete(mp: MediaPlayer?) {
        seekCompleteNotifier.onSeekComplete()
    }
	
    override fun onAudioFocusChange(focusState: Int) {
        when (focusState) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (mediaPlayer == null) {
                    initMediaPlayer("")
                } else if (!isPlaying()) {
                    play()
                }
                mediaPlayer?.setVolume(1.0f, 1.0f)
            }
			
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (mediaPlayer != null && isPlaying()) {
                    SharedPreferenceUtil.saveCurrentPosition(
                        sharedPreferences,
                        getCurrentPosition()
                    )
                    mediaPlayer?.stop()
                    notifyPlayPauseStateChanged()
                    stopForeground(false)
                    mediaPlayer?.release()
                    mediaPlayer = null
                }
            }
			
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying()) {
                    pause(false)
                }
            }
			
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying()) mediaPlayer?.setVolume(0.1f, 0.1f)
            }
			
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                if (isPlaying()) {
                    pause(false)
                }
            }
        }
    }
	
    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
        if (mediaPlayer != null) {
            with(sharedPreferences.edit()) {
                putInt(POSITION_KEY, position)
                apply()
            }
            SharedPreferenceUtil.saveCurrentPosition(sharedPreferences, getCurrentPosition())
            mediaPlayer?.stop()
            notifyPlayPauseStateChanged()
            mediaPlayer?.release()
        }
        if (requestAudioFocus()) {
            removeAudioFocus()
        }
    }
	
    fun playPause() {
        this.playPauseMusic()
    }
	
    fun playNext() {
        this.playNextSong()
    }
	
    fun playPrevious() {
        this.playPreviousSong()
    }
	
    private fun requestAudioFocus(): Boolean {
        val audioAttributes = AudioAttributes.Builder().run {
            setUsage(AudioAttributes.USAGE_MEDIA)
            setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(audioAttributes)
                setAcceptsDelayedFocusGain(true)
                setOnAudioFocusChangeListener(this@PlayerService)
                build()
            }
            focus = audioManager.requestAudioFocus(focusRequest)
        } else {
            focus = audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
		
        if (focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true
        return false
    }
	
    private fun removeAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED ==
                audioManager.abandonAudioFocusRequest(focusRequest)
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioManager.abandonAudioFocus(this)
        }
    }
	
    // Only initialize
    fun initMediaPlayer(songPath: String) {
        mediaPlayer?.reset()
        mediaPlayer = MediaPlayer()
        mediaPlayer?.setOnPreparedListener(this)
        mediaPlayer?.setOnSeekCompleteListener(this)
        mediaPlayer?.setOnCompletionListener(this)
        try {
            mediaPlayer?.setDataSource(songPath)
            mediaPlayer?.setOnPreparedListener {
                play()
            }
            mediaPlayer?.prepareAsync()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        setMediaSessionAction()
        setMediaSessionMetaData()
        val currentPosition = getSavedCurrentPosition()
        if (currentPosition != -1) {
            seekTo(currentPosition)
        }
    }
	
    fun setMediaSessionAction() {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(MEDIA_SESSION_ACTIONS)
            .setState(
                if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                getCurrentPosition().toLong(),
                1f
            )
		
        mediaSessionCompat.setPlaybackState(stateBuilder.build())
    }
	
    private fun setMediaSessionMetaData() {
        val metadata = MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Song Name")
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Album Name")
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Artist Name")
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer?.duration?.toLong() ?: 0L)
        }
        mediaSessionCompat.setMetadata(metadata.build())
    }
	
    fun getSongDurationMillis(): Int {
        if (mediaPlayer != null) {
            return mediaPlayer!!.duration
        }
        return -1
    }
	
    private fun play() {
        mediaPlayer?.let {
            if (!requestAudioFocus()) stopSelf()
            it.start()
            notifyPlayPauseStateChanged()
            startForegroundService()
        }
    }
	
    private fun pause(removeNotification: Boolean) {
        mediaPlayer?.pause()
        notifyPlayPauseStateChanged()
        stopForeground(removeNotification)
        SharedPreferenceUtil.saveCurrentPosition(sharedPreferences, getCurrentPosition())
    }
	
    fun seekTo(seekPosition: Int) {
        mediaPlayer?.seekTo(seekPosition)
        setMediaSessionAction()
    }
	
    private fun playPauseMusic() {
        if (mediaPlayer != null) {
            if (isPlaying()) {
                pause(false)
                with(sharedPreferences.edit()) {
                    putInt(POSITION_KEY, position)
                    apply()
                }
                if (requestAudioFocus()) {
                    removeAudioFocus()
                }
            } else {
                play()
            }
        } else {
            initMediaPlayer("https://www.learningcontainer.com/wp-content/uploads/2020/02/Kalimba.mp3")
        }
    }
	
    private fun playNextSong() {
        // initMediaPlayer(getNextPosition())
        // notifyCurrentSongChanged()
    }
	
    private fun playPreviousSong() {
        // initMediaPlayer(getPreviousPosition())
        // notifyCurrentSongChanged()
    }
	
    private fun notifyCurrentSongChanged() {
        currentSongChangeNotifier.onCurrentSongChange()
    }
	
    private fun notifyPlayPauseStateChanged() {
        playPauseStateNotifier.onPlayPauseStateChange()
        setMediaSessionAction()
    }
	
    private fun getNextPosition(): Int {
        var nextPosition = position + 1
        if (position == -1) {
            nextPosition = SharedPreferenceUtil.getPosition(sharedPreferences) + 1
        }
        position = nextPosition
        return nextPosition
    }
	
    private fun getPreviousPosition(): Int {
        var prePosition = position - 1
        if (position == -1) {
            prePosition = SharedPreferenceUtil.getPosition(sharedPreferences) - 1
        }
        position = prePosition
        return prePosition
    }
	
    private fun getSavedCurrentPosition(): Int {
        return sharedPreferences.getInt(CURRENT_SONG_DURATION_KEY, -1)
    }
	
    fun getCurrentPosition(): Int {
        mediaPlayer?.let {
            return it.currentPosition
        }
        return 0
    }
	
    fun setSongChangeCallback(callback: SongChangeNotifier) {
        this.currentSongChangeNotifier = callback
    }
	
    fun setPlayPauseStateCallback(callback: PlayPauseStateNotifier) {
        this.playPauseStateNotifier = callback
    }
	
    fun setSeekCompleteNotifierCallback(callback: SeekCompletionNotifier) {
        this.seekCompleteNotifier = callback
    }
	
    fun restartNotification() {
        startForegroundService()
    }
	
    fun isPlaying(): Boolean {
        mediaPlayer?.let {
            return it.isPlaying
        }
        return false
    }
	
    private fun startForegroundService() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
		
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(notificationManager)
        }
		
        val mainIntent = Intent(this, MainActivity::class.java).also {
            it.action = ACTION_MAIN
        }
        val mainPendingIntent =
            PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)
		
        val previousIntent = Intent(this, NotificationActionBroadcastReceiver::class.java).also {
            it.action = ACTION_PREVIOUS
        }
        val previousPendingIntent =
            PendingIntent.getBroadcast(this, 0, previousIntent, PendingIntent.FLAG_IMMUTABLE)
		
        val playPauseIntent = Intent(this, NotificationActionBroadcastReceiver::class.java).also {
            it.action = ACTION_PLAY_PAUSE
        }
        val playPausePendingIntent =
            PendingIntent.getBroadcast(this, 0, playPauseIntent, PendingIntent.FLAG_IMMUTABLE)
		
        val nextIntent = Intent(this, NotificationActionBroadcastReceiver::class.java).also {
            it.action = ACTION_NEXT
        }
        val nextPendingIntent =
            PendingIntent.getBroadcast(this, 0, nextIntent, PendingIntent.FLAG_IMMUTABLE)
		
        val imgByte = getSongThumbnail("")
        val bitmap = if (imgByte != null) {
            BitmapFactory.decodeByteArray(imgByte, 0, imgByte.size)
        } else {
            BitmapFactory.decodeResource(this.resources, R.drawable.ic_album)
        }
		
        var playPauseDrawable = R.drawable.ic_pause_bigger
        if (mediaPlayer != null) {
            playPauseDrawable = if (isPlaying()) {
                R.drawable.ic_pause_bigger
            } else {
                R.drawable.ic_play_bigger
            }
        }
		
        val builder = androidx.core.app.NotificationCompat
            .Builder(this, NOTIFICATION_CHANNEL_ID).setOngoing(true).apply {
                setContentIntent(mainPendingIntent)
                priority = androidx.core.app.NotificationCompat.PRIORITY_MAX
                setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
                setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                setContentTitle("Song Name")
                setContentText("Artist Name")
                setSubText("Album Name")
                setOngoing(isPlaying())
                addAction(R.drawable.ic_previous, "Previous", previousPendingIntent)
                addAction(playPauseDrawable, "Play", playPausePendingIntent)
                addAction(R.drawable.ic_next, "Next", nextPendingIntent)
                setLargeIcon(bitmap)
                setSmallIcon(R.drawable.ic_song)
                setShowWhen(false)
                // Take advantage of MediaStyle features
                setStyle(
                    NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSessionCompat.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
            }
		
        startForeground(NOTIFICATION_ID, builder.build())
    }
	
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            IMPORTANCE_LOW
        )
        channel.description = "The playing notification provides actions for play/pause etc."
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        notificationManager.createNotificationChannel(channel)
    }
	
    inner class LocalBinder : Binder() {
        fun getService(): PlayerService {
            return this@PlayerService
        }
    }
}

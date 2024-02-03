package com.think.music

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.think.music.databinding.ActivityMainBinding
import com.think.music.services.PlayerService
import com.think.music.util.MusicPlayerRemote
import com.think.music.util.PlayPauseStateNotifier
import com.think.music.util.PlayerHelper.getSongThumbnail
import com.think.music.util.SeekCompletionNotifier
import com.think.music.util.SongChangeNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity :
    AppCompatActivity(),
    SongChangeNotifier,
    PlayPauseStateNotifier,
    SeekCompletionNotifier {
	
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private var serviceToken: MusicPlayerRemote.ServiceToken? = null
    private val playerService: PlayerService? get() = MusicPlayerRemote.playerService
	
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initializeSetUp()
    }
	
    private fun initializeSetUp() {
        serviceToken = MusicPlayerRemote.bindToService(
            this@MainActivity,
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                }
				
                override fun onServiceDisconnected(name: ComponentName) {
                }
            }
        )
        setUpPlayPauseButton()
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    MusicPlayerRemote.seekTo(progress)
                    binding.txtStartDuration.text = millisToString(progress)
                }
            }
			
            override fun onStartTrackingTouch(p0: SeekBar?) = Unit
            override fun onStopTrackingTouch(p0: SeekBar?) = Unit
        })
		
        binding.fabPlayPause.setOnClickListener {
            if (isNetworkAvailable(this@MainActivity)) {
                if (playerService != null) {
                    playerService!!.setSongChangeCallback(this)
                    playerService!!.setPlayPauseStateCallback(this)
                    playerService!!.setSeekCompleteNotifierCallback(this)
                    if (playerService?.mediaPlayer != null) {
                        MusicPlayerRemote.playPause()
                    } else {
                        CoroutineScope(Dispatchers.Main).launch {
                            binding.ivPlayPause.visibility = View.GONE
                            binding.audioProgress.visibility = View.VISIBLE
                            delay(200)
                            MusicPlayerRemote.playerService?.initMediaPlayer("https://www.learningcontainer.com/wp-content/uploads/2020/02/Kalimba.mp3")
                        }
                    }
                }
            } else {
                Toast.makeText(this@MainActivity, "Check your internet", Toast.LENGTH_SHORT).show()
            }
        }
    }
	
    private fun millisToString(duration: Int): String {
        val minutes = duration / 1000 / 60
        val seconds = duration / 1000 % 60
		
        var timeString = "$minutes:"
        if (minutes < 10) {
            timeString = "0$minutes:"
        }
        if (seconds < 10) timeString += "0"
        timeString += seconds
		
        return timeString
    }
	
    private fun setUpPlayPauseButton() {
        if (playerService != null &&
            playerService!!.mediaPlayer != null &&
            playerService!!.isPlaying()
        ) {
            binding.ivPlayPause.setImageResource(R.drawable.ic_pause)
            val imgByte = getSongThumbnail("https://www.learningcontainer.com/wp-content/uploads/2020/02/Kalimba.mp3")
            Glide.with(this@MainActivity).asBitmap().load(imgByte).error(R.drawable.ic_album)
                .into(binding.imgThumbnail)
        } else {
            binding.ivPlayPause.setImageResource(R.drawable.ic_play)
        }
    }
	
    private fun setUpSeekBar() = lifecycleScope.launch(Dispatchers.Main) {
        binding.txtEndDuration.text = millisToString(MusicPlayerRemote.songDurationMillis)
        binding.txtStartDuration.text = millisToString(MusicPlayerRemote.currentSongPositionMillis)
        binding.seekBar.max = MusicPlayerRemote.songDurationMillis
        if (playerService?.mediaPlayer != null) {
            try {
                binding.seekBar.progress = MusicPlayerRemote.currentSongPositionMillis
                while (playerService?.mediaPlayer!!.isPlaying) {
                    binding.txtStartDuration.text =
                        millisToString(MusicPlayerRemote.currentSongPositionMillis)
                    binding.seekBar.progress = MusicPlayerRemote.currentSongPositionMillis
                    delay(100)
                }
            } catch (e: NullPointerException) {
                e.printStackTrace()
            }
        }
    }
	
    override fun onPlayPauseStateChange() {
        binding.ivPlayPause.visibility = View.VISIBLE
        binding.audioProgress.visibility = View.GONE
        setUpSeekBar()
        setUpPlayPauseButton()
        if (playerService != null) {
            playerService!!.setMediaSessionAction()
            playerService!!.restartNotification()
        }
    }
	
    override fun onSeekComplete() {
    }
	
    override fun onCurrentSongChange() {
    }
	
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivityManager.activeNetworkInfo != null && connectivityManager.activeNetworkInfo!!
            .isConnected
    }
}

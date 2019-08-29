package hu.csomorbalazs.runbeat.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.support.annotation.RequiresApi
import android.support.v4.app.NotificationCompat
import android.util.Log
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import hu.csomorbalazs.runbeat.Constants.Companion.ACTION_AUTO_DETECT_OFF
import hu.csomorbalazs.runbeat.Constants.Companion.ACTION_AUTO_DETECT_ON
import hu.csomorbalazs.runbeat.Constants.Companion.ACTION_BROADCAST_BPM
import hu.csomorbalazs.runbeat.Constants.Companion.ACTION_BROADCAST_STATE
import hu.csomorbalazs.runbeat.Constants.Companion.ACTION_NEXT
import hu.csomorbalazs.runbeat.Constants.Companion.ACTION_START
import hu.csomorbalazs.runbeat.Constants.Companion.ACTION_STOP
import hu.csomorbalazs.runbeat.Constants.Companion.ACTION_UPDATE_CURRENT_BPM
import hu.csomorbalazs.runbeat.Constants.Companion.ACTION_UPDATE_MUSIC_SOURCE
import hu.csomorbalazs.runbeat.Constants.Companion.ACTION_UPDATE_SPOTIFY
import hu.csomorbalazs.runbeat.Constants.Companion.serviceRunning
import hu.csomorbalazs.runbeat.MainActivity
import hu.csomorbalazs.runbeat.MusicSourceActivity
import hu.csomorbalazs.runbeat.MusicSourceActivity.Companion.MUSIC_SOURCE_KEY
import hu.csomorbalazs.runbeat.MusicSourceActivity.Companion.MUSIC_SOURCE_PREF
import hu.csomorbalazs.runbeat.MusicSourceActivity.Companion.MY_LIBRARY
import hu.csomorbalazs.runbeat.MusicSourceActivity.Companion.PLAYLIST
import hu.csomorbalazs.runbeat.MusicSourceActivity.Companion.SPOTIFY_RUNNING
import hu.csomorbalazs.runbeat.R
import hu.csomorbalazs.runbeat.model.MyTrack
import kaaes.spotify.webapi.android.SpotifyService
import kotlin.random.Random

class MusicService : Service(), SensorEventListener {
    companion object {
        //Notification channel and notification ids
        private const val NOTIFICATION_CHANNEL_ID = "NOTIFICATION_CHANNEL_ID"
        private const val NOTIFICATION_CHANNEL_NAME = "RunBeat is running in background"
        private const val NOTIF_FOREGROUND_ID = 101

        //Keys for intent extras
        const val EXTRA_BPM = "EXTRA BPM"
        const val EXTRA_STEPCOUNTING = "EXTRA_STEPCOUNTING"

        //ID of Spotify running playlist
        private const val RUNNING_PLAYLIST_ID = "37i9dQZF1DWT6anPZiHuxz"

        //Constants for selecting songs
        private const val ENERGY_LIMIT = 0.5F
        private const val DANCEABILITY_LIMIT = 0.5F
        private const val BPM_TOLERANCE = 0.05F

        private const val LOG_TAG = "MusicService"
    }

    private var webSpotify: SpotifyService? = null
    private var spotifyAppRemote: SpotifyAppRemote? = null


    override fun onCreate() {
        super.onCreate()

        //Start thread of song change handler
        songChangeHandlerThread.start()
        songChangeHandler = Handler(songChangeHandlerThread.looper)
    }

    private lateinit var songChangeHandler: Handler
    private val songChangeHandlerThread = HandlerThread("songChangeHandlerThread")

    private var currentBPM: Float = 140F

    private var seedReady = false
    private var isPaused = true
    private var isStepCounting = false

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        when (intent.action) {
            ACTION_START -> {
                spotifyAppRemote = MainActivity.mSpotifyAppRemote
                webSpotify = MainActivity.webSpotify

                spotifyAppRemote?.playerApi
                    ?.subscribeToPlayerState()
                    ?.setEventCallback { onPlayerState(it) }

                getMusicSource()

                startForeground(
                    NOTIF_FOREGROUND_ID,
                    getMyNotification("...")
                )

            }

            ACTION_UPDATE_SPOTIFY -> {
                spotifyAppRemote = MainActivity.mSpotifyAppRemote
                webSpotify = MainActivity.webSpotify

                spotifyAppRemote?.playerApi
                    ?.subscribeToPlayerState()
                    ?.setEventCallback { onPlayerState(it) }
            }

            ACTION_UPDATE_MUSIC_SOURCE -> getMusicSource()

            ACTION_NEXT -> {
                isPaused = false

                songChangeHandler.removeCallbacksAndMessages(null)

                songChangeHandler.postDelayed({
                    changeTrack()
                }, 0)
            }

            ACTION_AUTO_DETECT_ON -> startStepCounting()

            ACTION_AUTO_DETECT_OFF -> stopStepCounting()

            ACTION_UPDATE_CURRENT_BPM -> {
                Log.d(LOG_TAG, ACTION_UPDATE_CURRENT_BPM)

                currentBPM = intent.getIntExtra(EXTRA_BPM, currentBPM.toInt()).toFloat()

                if (currentBPM !in (currentTrack.tempo - 5F)..(currentTrack.tempo + 5F)) {

                    songChangeHandler.removeCallbacksAndMessages(null)

                    songChangeHandler.postDelayed({
                        changeTrack()
                    }, 1000)
                }
            }

            ACTION_BROADCAST_STATE -> {
                val stateBroadcastIntent = Intent(ACTION_BROADCAST_STATE)
                stateBroadcastIntent.putExtra(EXTRA_STEPCOUNTING, isStepCounting)
                stateBroadcastIntent.putExtra(EXTRA_BPM, currentBPM)

                sendBroadcast(stateBroadcastIntent)
            }

            ACTION_STOP -> stopService()
        }

        return START_STICKY
    }

    private fun changeTrack() {
        Log.d(LOG_TAG, "changeTrack")

        if (seedReady && !isPaused) {
            nextTrack = selectNextTrack()
            if (seedTracks.contains(nextTrack)) seedTracks.remove(nextTrack)

            spotifyAppRemote?.playerApi?.play(nextTrack.uri)
        }
    }

    private var currentTrack: MyTrack = MyTrack("currentTrack", currentBPM)
    private var nextTrack: MyTrack = MyTrack("nextTrack", currentBPM)

    private fun onPlayerState(playerState: PlayerState) {
        if (serviceRunning) updateNotification("${playerState.track.name} - ${currentBPM.toInt()} BPM")

        //If the player is paused upcoming song changes should be ignored
        if (playerState.isPaused) {
            isPaused = true
            songChangeHandler.removeCallbacksAndMessages(null)
            return
        } else {
            isPaused = false
        }

        //If the current track changed, currentTrack is updated
        if (currentTrack.uri != playerState.track.uri) {
            Log.d(LOG_TAG, "onPlayerState -> track changed")

            currentTrack.uri = playerState.track.uri

            AsyncTask.execute {
                if (playerState.track.uri.contains(":track:")) {
                    val id = playerState.track.uri.split(":track:")[1]
                    val tempo = webSpotify?.getTrackAudioFeatures(id)?.tempo
                    currentTrack.id = id
                    currentTrack.tempo = tempo ?: currentBPM
                }
            }
        }

        //Calculate the delay to change tracks 2s before current track ends
        val delay = playerState.track.duration - playerState.playbackPosition - 2000

        if (delay > 0) {
            songChangeHandler.removeCallbacksAndMessages(null)
            songChangeHandler.postDelayed({
                changeTrack()
            }, delay)
        }
    }

    //Returns selected next track from the seed or gets a recommendation
    private fun selectNextTrack(): MyTrack {
        Log.d(LOG_TAG, "selectNextTrack")

        //If ideal track is in seedTracks, return that
        for (track in seedTracks) {
            if (track.tempo > currentBPM) {
                if ((track.tempo - currentBPM) > currentBPM * BPM_TOLERANCE) break

                Log.d(LOG_TAG, "currentBPM: $currentBPM, track.tempo: ${track.tempo}")
                return track
            }
        }

        //...else get a recommendation from the web api
        val energy = 0.5F
        val targetTempo = currentBPM
        val minTempo = currentBPM * (1F - BPM_TOLERANCE)
        val maxTempo = currentBPM * (1F + BPM_TOLERANCE)

        val requestMap: MutableMap<String, Any> = mutableMapOf(
            "target_tempo" to targetTempo,
            "min_tempo" to minTempo,
            "max_tempo" to maxTempo,
            "target_energy" to energy
        )

        requestMap["seed_tracks"] = getRandomSeed()

        return getRecommendation(requestMap)
    }

    //Return a string from 5 random seed track ids
    private fun getRandomSeed(): String {
        var seed = ""

        for (i in 0..4) {
            val random = Random.nextInt(0, seedTracks.size - 1)
            seed += "${seedTracks[random].id},"
        }

        return seed
    }

    //Gets recommendation from the Web API, tries again with different request if unsuccessful
    private fun getRecommendation(requestMap: MutableMap<String, Any>): MyTrack {
        val tracks = webSpotify?.getRecommendations(requestMap)?.tracks

        return if (tracks?.size != 0 && tracks?.get(0) != null && tracks[0].id != currentTrack.id) {
            MyTrack(tracks[0].id, currentBPM)
        } else {
            if (requestMap["target_energy"] as Float > 0.0F)
                requestMap["target_energy"] = requestMap["target_energy"] as Float - 0.1F
            requestMap["seed_tracks"] = getRandomSeed()

            return getRecommendation(requestMap)
        }
    }

    //Returns a list of track ids from the selected music source
    private fun getMusicSeed(): List<String> {
        Log.d(LOG_TAG, "getMusicSeed")

        val tracks = mutableListOf<String>()

        when (musicSource) {
            SPOTIFY_RUNNING -> {
                val runningTracks =
                    webSpotify?.getPlaylistTracks("spotify", RUNNING_PLAYLIST_ID)?.items

                runningTracks?.forEach {
                    tracks.add(it.track.id)
                }
            }

            MY_LIBRARY -> {
                var offset = 0

                while (true) {
                    val currentTracks = webSpotify?.getMySavedTracks(
                        mapOf(
                            "limit" to 50,
                            "offset" to offset
                        )
                    )?.items

                    if (currentTracks != null && currentTracks.size != 0) {
                        currentTracks.forEach {
                            tracks.add(it.track.id)
                        }
                        if (currentTracks.size < 50) break
                    } else break

                    offset += 50
                }
            }

            PLAYLIST -> {
                val playlistTracks =
                    webSpotify?.getPlaylistTracks(webSpotify?.me?.id, selectedPlaylist)?.items

                playlistTracks?.forEach {
                    tracks.add(it.track.id)
                }
            }
        }

        return tracks
    }

    //Returns a list of seed tracks with their tempo, sorted and selected by energy and danceability
    private fun getTempoOfTracks(tracks: List<String>): List<MyTrack> {

        val list: MutableList<MyTrack> = mutableListOf()

        for (i in 0 until tracks.size step 100) {
            var trackIds = ""

            var upperLimit = 100
            if (i + 100 >= tracks.size) upperLimit = tracks.size - 1 - i

            for (j in 0 until upperLimit) {
                trackIds += "${tracks[i + j]},"
            }

            val audioFeatures = webSpotify?.getTracksAudioFeatures(trackIds)?.audio_features
            audioFeatures?.forEach {
                if (it.energy > ENERGY_LIMIT && it.danceability > DANCEABILITY_LIMIT) {
                    list.add(MyTrack(it.id, it.tempo))
                }
            }
        }

        return list.sortedBy { it.tempo }
    }

    private var musicSource: Int = -1
    private var selectedPlaylist: String? = null
    private var seedTracks: MutableList<MyTrack> = mutableListOf()

    //Reads the music source from the SharedPreferences and refreshes seedTracks
    private fun getMusicSource() {
        Log.d(LOG_TAG, "getMusicSource")

        val sp = getSharedPreferences(MUSIC_SOURCE_PREF, Context.MODE_PRIVATE)
        val newMusicSource = sp.getInt(MUSIC_SOURCE_KEY, SPOTIFY_RUNNING)
        val newSelectedPlaylist: String?

        //If music source is a playlist, get playlist id
        if (newMusicSource == PLAYLIST) {
            newSelectedPlaylist = sp.getString(MusicSourceActivity.PLAYLIST_KEY, null)
        } else {
            newSelectedPlaylist = null
        }

        //If music source changed, load music into seedTracks
        if (newMusicSource != musicSource || newSelectedPlaylist != selectedPlaylist) {
            musicSource = newMusicSource
            selectedPlaylist = newSelectedPlaylist

            AsyncTask.execute {
                seedTracks = getTempoOfTracks(getMusicSeed()).toMutableList()
                seedReady = true
            }
        }
    }

    //Handling notifications
    private fun updateNotification(text: String) {
        val notification = getMyNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
        manager?.notify(NOTIF_FOREGROUND_ID, notification)
    }

    private fun getMyNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIF_FOREGROUND_ID,
            notificationIntent,
            PendingIntent.FLAG_CANCEL_CURRENT
        )

        return NotificationCompat.Builder(
            this, NOTIFICATION_CHANNEL_ID
        )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle("RunBeat is running")
            .setShowWhen(false)
            .setContentText(text)
            .setSmallIcon(R.drawable.shoe_notification)
            .setContentIntent(contentIntent).build()
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )

        val notificationManager: NotificationManager? =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?

        notificationManager?.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent): IBinder? = null

    private var sensorManager: SensorManager? = null
    private var stepCounterSensor: Sensor? = null
    private lateinit var broadcastIntent: Intent

    //Start counting the current BPM
    private fun startStepCounting() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        sensorManager?.registerListener(this, stepCounterSensor, 0)

        stepCount = 0
        lastSamplingTime = System.currentTimeMillis()

        broadcastIntent = Intent(ACTION_BROADCAST_BPM)
        isStepCounting = true
    }

    //Stop counting the current BPM
    private fun stopStepCounting() {
        sensorManager?.unregisterListener(this)

        isStepCounting = false
    }

    private var stepCount: Int = 0
    private var currentSamplingTime: Long = 0
    private var lastSamplingTime: Long = 0
    private var bpm: Float = 0F
    private var lastBpm: Float = 0F

    //Calculate current BPM
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor == stepCounterSensor) {
            stepCount++

            //Calculate steps per minute every 15 steps
            if (stepCount >= 15) {
                currentSamplingTime = System.currentTimeMillis()

                //Calculate bpm
                bpm = stepCount / (currentSamplingTime - lastSamplingTime).toFloat() * 60000F

                //Reset variables
                stepCount = 0
                lastSamplingTime = currentSamplingTime

                //If bpm is unrealistic return
                if (bpm > 240) return

                Log.d("STEPCOUNT", "lastBpm: $lastBpm, bpm: $bpm")

                //Only change currentBPM if the last two values were close
                if ((lastBpm - bpm) in -5F..5F) {
                    currentBPM = bpm
                }

                lastBpm = bpm

                //If there is significant change in tempo, change track
                if (currentBPM - currentTrack.tempo !in (-10F..10F)) {
                    AsyncTask.execute {
                        changeTrack()
                    }
                }

                //Send broadcast, so the BPM could be displayed on the UI
                broadcastIntent.action = ACTION_BROADCAST_BPM
                broadcastIntent.putExtra(EXTRA_BPM, currentBPM)
                sendBroadcast(broadcastIntent)
            }
        }
    }

    //Needed for interface implementation, empty function
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    //Stops the foreground service
    private fun stopService() {
        spotifyAppRemote?.playerApi?.pause()
        SpotifyAppRemote.disconnect(spotifyAppRemote)
        stopStepCounting()
        stopForeground(true)
        stopSelf()
        serviceRunning = false
    }
}
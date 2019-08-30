package hu.csomorbalazs.runbeat

import android.content.*
import android.os.Build
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils.loadAnimation
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import com.spotify.sdk.android.authentication.AuthenticationClient
import com.spotify.sdk.android.authentication.AuthenticationRequest
import com.spotify.sdk.android.authentication.AuthenticationResponse
import com.spotify.sdk.android.authentication.LoginActivity.REQUEST_CODE
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
import hu.csomorbalazs.runbeat.Constants.Companion.SPOTIFY_PACKAGE_NAME
import hu.csomorbalazs.runbeat.Constants.Companion.serviceRunning
import hu.csomorbalazs.runbeat.model.CounterHandler
import hu.csomorbalazs.runbeat.service.MusicService
import hu.csomorbalazs.runbeat.util.isNetworkAvailable
import hu.csomorbalazs.runbeat.util.isPackageInstalled
import hu.csomorbalazs.runbeat.util.openPlayStoreForApp
import kaaes.spotify.webapi.android.SpotifyApi
import kaaes.spotify.webapi.android.SpotifyService
import kaaes.spotify.webapi.android.models.UserPrivate
import kotlinx.android.synthetic.main.activity_main.*
import retrofit.Callback
import retrofit.RetrofitError
import retrofit.client.Response

class MainActivity : AppCompatActivity(), CounterHandler.CounterListener {

    companion object {
        private const val MAX_BPM = 220
        private const val MIN_BPM = 80
        private const val REDIRECT_URI = "testschema://callback"
        private const val CLIENT_ID = "cad943e35ce74a4c94a70016befdadc4"
        private const val ACCESS_TOKEN_KEY = "ACCESS_TOKEN_KEY"
        private const val PREF_NAME: String = "SharedPreferences"

        var webSpotify: SpotifyService? = null
        var mSpotifyAppRemote: SpotifyAppRemote? = null
    }

    private var currentBPM = 140

    private var isPaused = false
    private var accessToken: String? = null

    private lateinit var counterHandler: CounterHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvBPM.text = currentBPM.toString()

        btnPlay.setOnClickListener {
            isPaused = if (isPaused) {
                resume()
                false
            } else {
                stop()
                true
            }
        }

        btnNext.setOnClickListener {
            sendActionToMusicService(ACTION_NEXT)
        }

        btnChoose.setOnClickListener {
            startActivity(Intent(this, MusicSourceActivity::class.java))
        }

        btnAutoDetect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                btnMinus.isEnabled = false
                btnPlus.isEnabled = false

                sendActionToMusicService(ACTION_AUTO_DETECT_ON)

            } else {
                btnMinus.isEnabled = true
                btnPlus.isEnabled = true

                counterHandler.setStartNumber(currentBPM)

                sendActionToMusicService(ACTION_AUTO_DETECT_OFF)
            }
        }

        counterHandler = CounterHandler.Builder()
            .decrementalView(btnMinus)
            .incrementalView(btnPlus)
            .startNumber(currentBPM)
            .minRange(MIN_BPM)
            .maxRange(MAX_BPM)
            .isCycle(false)
            .counterDelay(170)
            .counterStep(10)
            .listener(this)
            .build()

        if (checkIfSpotifyIsInstalled()) {
            checkInternetConnection()
        }

        accessToken =
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(ACCESS_TOKEN_KEY, null)
    }

    private fun checkIfSpotifyIsInstalled(): Boolean {
        if (!isPackageInstalled(SPOTIFY_PACKAGE_NAME, packageManager)) {
            val alertDialog: AlertDialog = this.let {
                val builder = AlertDialog.Builder(it)
                builder.apply {
                    setTitle("Spotify not installed")
                    setIcon(getDrawable(R.drawable.error))
                    setMessage("This app uses Spotify to play music. Install the Spotify app and try again.")

                    setPositiveButton("Install Spotify") { _, _ ->
                        finishAndRemoveTask()
                        this@MainActivity.openPlayStoreForApp(SPOTIFY_PACKAGE_NAME)
                    }

                    setNegativeButton(getString(R.string.exit)) { _, _ ->
                        finishAndRemoveTask()
                    }

                    setOnCancelListener {
                        finishAndRemoveTask()
                    }

                    setOnKeyListener { _, keyCode, _ ->
                        if (keyCode == KeyEvent.KEYCODE_BACK) {
                            finishAndRemoveTask()
                        }
                        true
                    }
                }
                builder.create()
            }

            alertDialog.show()

            return false
        } else {
            return true
        }
    }

    private fun checkInternetConnection() {
        if (!this.isNetworkAvailable()) {
            SpotifyAppRemote.disconnect(mSpotifyAppRemote)

            val alertDialog: AlertDialog = this.let {
                val builder = AlertDialog.Builder(it)
                builder.apply {
                    setTitle(getString(R.string.no_connection))
                    setIcon(getDrawable(R.drawable.error))
                    setMessage(getString(R.string.runbeat_could_not_connect))

                    setPositiveButton(getString(R.string.retry)) { _, _ ->
                        checkInternetConnection()
                    }

                    setNegativeButton(getString(R.string.exit)) { _, _ ->
                        finishAndRemoveTask()
                    }

                    setOnCancelListener {
                        finishAndRemoveTask()
                    }

                    setOnKeyListener { _, keyCode, _ ->
                        if (keyCode == KeyEvent.KEYCODE_BACK) {
                            finishAndRemoveTask()
                        }
                        true
                    }
                }
                builder.create()
            }

            alertDialog.show()
        }
    }

    private fun sendActionToMusicService(action: String) {
        val intentService = Intent(this@MainActivity, MusicService::class.java)
        intentService.action = action
        startService(intentService)
    }

    private fun sendBPMToMusicService() {
        val intentService = Intent(this@MainActivity, MusicService::class.java)
        intentService.action = ACTION_UPDATE_CURRENT_BPM
        intentService.putExtra(MusicService.EXTRA_BPM, currentBPM)
        startService(intentService)
    }

    //Broadcast receiver that receives the steps count from the MusicService
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            currentBPM = intent.getFloatExtra(MusicService.EXTRA_BPM, 0F).toInt()
            tvBPM.text = currentBPM.toString()

        }
    }

    //Broadcast receiver that receives the current BPM and state of step counting to update the UI
    private val stateBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            currentBPM = intent.getFloatExtra(MusicService.EXTRA_BPM, 0F).toInt()
            tvBPM.text = currentBPM.toString()
            counterHandler.setStartNumber(currentBPM)

            if (intent.getBooleanExtra(MusicService.EXTRA_STEPCOUNTING, false)) {
                btnAutoDetect.isChecked = true
            }
        }
    }

    override fun onStart() {
        super.onStart()

        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        //Starting SpotifyAppRemote connection
        SpotifyAppRemote.disconnect(mSpotifyAppRemote)
        SpotifyAppRemote.connect(this, connectionParams,
            object : Connector.ConnectionListener {
                override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                    mSpotifyAppRemote = spotifyAppRemote

                    mSpotifyAppRemote!!.playerApi
                        .subscribeToPlayerState()
                        .setEventCallback { onPlayerState(it) }

                    //Start music playing service
                    startServiceIfReady()
                }

                override fun onFailure(throwable: Throwable) {
                    Log.e("MainActivity", throwable.message, throwable)
                }
            })

        //Connect to Spotify web api
        if (accessToken == null) {
            getNewAccessToken()
        } else {
            connectWebSpotify()
        }

        //Register broadcast receivers
        registerReceiver(stateBroadcastReceiver, IntentFilter(ACTION_BROADCAST_STATE))
        registerReceiver(broadcastReceiver, IntentFilter(ACTION_BROADCAST_BPM))

        //If service is already running, send intents to update music source and to broadcast state for UI update
        if (serviceRunning) {
            sendActionToMusicService(ACTION_UPDATE_MUSIC_SOURCE)
            sendActionToMusicService(ACTION_BROADCAST_STATE)
        }
    }

    private fun getNewAccessToken() {
        val builder = AuthenticationRequest.Builder(
            CLIENT_ID,
            AuthenticationResponse.Type.TOKEN,
            REDIRECT_URI
        )

        builder.setScopes(
            arrayOf(
                "streaming",
                "user-library-read",
                "playlist-read-private",
                "playlist-read-collaborative"
            )
        )
        val request = builder.build()

        AuthenticationClient.openLoginActivity(this, REQUEST_CODE, request)
    }

    //Spotify LoginActivity result
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        if (requestCode == REQUEST_CODE) {
            val response = AuthenticationClient.getResponse(resultCode, intent)
            when (response.type) {
                AuthenticationResponse.Type.TOKEN -> {
                    accessToken = response.accessToken
                    connectWebSpotify()
                }

                AuthenticationResponse.Type.ERROR -> {

                }
                else -> {
                }
            }
        }
    }

    //Connect to the Web API
    private fun connectWebSpotify() {
        val api = SpotifyApi()
        api.setAccessToken(accessToken)

        webSpotify = api.service

        webSpotify?.getMe(object : Callback<UserPrivate> {
            override fun success(t: UserPrivate, response: Response) {
                startServiceIfReady()
            }

            override fun failure(error: RetrofitError?) {
                getNewAccessToken()
            }
        })
    }

    //Start service, or if already running send update Spotify intent
    private var ready: Int = -1

    private fun startServiceIfReady() {
        ready++

        if (ready >= 1) {

            if (!serviceRunning) {

                val intentService = Intent(this@MainActivity, MusicService::class.java)
                intentService.action = ACTION_START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intentService)
                } else {
                    startService(intentService)
                }

                serviceRunning = true
            } else {
                sendActionToMusicService(ACTION_UPDATE_SPOTIFY)
            }
        }
    }

    //Shows current track artist, title and album cover on UI
    private fun onPlayerState(playerState: PlayerState) {
        isPaused = playerState.isPaused
        if (isPaused) btnPlay.setBackgroundResource(R.drawable.play_button)
        else btnPlay.setBackgroundResource(R.drawable.pause_button)

        val currentTrack = playerState.track

        //Only sets title and artist if its not null
        if (currentTrack.name == null) {
            //add loading to image
            ivCover.visibility = View.INVISIBLE
            prBar.visibility = View.VISIBLE
        } else {
            tvArtist.text = currentTrack.artist.name
            tvTitle.text = currentTrack.name
        }

        mSpotifyAppRemote!!.imagesApi.getImage(currentTrack.imageUri).setResultCallback {
            prBar.visibility = View.GONE
            ivCover.setImageBitmap(it)
            ivCover.visibility = View.VISIBLE
        }
    }

    override fun onStop() {
        super.onStop()

        //Unregister broadcast receivers
        unregisterReceiver(broadcastReceiver)
        unregisterReceiver(stateBroadcastReceiver)

        //Saving the accessToken
        val sp: SharedPreferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val editor: SharedPreferences.Editor = sp.edit()
        editor.putString(ACCESS_TOKEN_KEY, accessToken)
        editor.apply()
    }


    //Open a dialog to ask the user if the service should be stopped
    override fun onBackPressed() {
        val alertDialog: AlertDialog = this.let {
            val builder = AlertDialog.Builder(it)
            builder.apply {
                setTitle(getString(R.string.quit_runbeat))
                setMessage(getString(R.string.quit_or_background))

                setPositiveButton(getString(R.string.quit)) { _, _ ->
                    sendActionToMusicService(ACTION_STOP)
                    super.onBackPressed()
                }
                setNegativeButton(getString(R.string.continue_in_backgrund)) { _, _ ->
                    super.onBackPressed()
                }
            }
            builder.create()
        }
        alertDialog.show()
    }


    //Continue playing current track
    private fun resume() {
        mSpotifyAppRemote?.playerApi?.resume()
    }

    //Stop playing current track
    private fun stop() {
        mSpotifyAppRemote?.playerApi?.pause()
    }


    //On plus button pressed
    override fun onIncrement(view: View?, number: Int) {
        currentBPM = number
        tvBPM.text = currentBPM.toString()
        sendBPMToMusicService()
        runAnimation(tvBPM)
    }

    //On minus button pressed
    override fun onDecrement(view: View?, number: Int) {
        currentBPM = number
        tvBPM.text = currentBPM.toString()
        sendBPMToMusicService()
        runAnimation(tvBPM)
    }

    //Scale animation on view
    private fun runAnimation(v: View) {
        val animation = loadAnimation(this, R.anim.scale)
        animation.reset()

        v.clearAnimation()
        v.startAnimation(animation)
    }

    //Create menu
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    //Start settings activity on settings icon click
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                val intentSettings = Intent(this, SettingsActivity::class.java)
                startActivity(intentSettings)
            }
        }

        return true
    }
}

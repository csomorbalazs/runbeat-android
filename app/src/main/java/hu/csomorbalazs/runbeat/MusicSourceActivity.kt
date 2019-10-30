package hu.csomorbalazs.runbeat

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import hu.csomorbalazs.runbeat.util.checkWithoutAnimation
import hu.csomorbalazs.runbeat.util.disable
import hu.csomorbalazs.runbeat.util.enable
import kaaes.spotify.webapi.android.models.Pager
import kaaes.spotify.webapi.android.models.PlaylistSimple
import kotlinx.android.synthetic.main.activity_music_source.*
import retrofit.Callback
import retrofit.RetrofitError
import retrofit.client.Response

class MusicSourceActivity : AppCompatActivity() {

    companion object {
        const val MUSIC_SOURCE_PREF = "MUSIC_SOURCE_PREF"
        const val MUSIC_SOURCE_KEY = "MUSIC_SOURCE_KEY"
        const val PLAYLIST_KEY = "PLAYLIST_KEY"
        const val SPOTIFY_RUNNING = 0
        const val MY_LIBRARY = 1
        const val PLAYLIST = 2
    }

    private var musicSource: Int = SPOTIFY_RUNNING
    var selectedPlaylist: String? = null
    var playlistIDs = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_source)

        //Disabling Save button
        btnSave.disable()

        //Add formated text to the radioButtons
        val txtRunning =
            SpannableString(getString(R.string.spotify_running) + "\n" + getString(R.string.spotify_running_description))
        val txtMyLibrary =
            SpannableString(getString(R.string.my_library) + "\n" + getString(R.string.my_library_description))
        val txtPlaylist =
            SpannableString(getString(R.string.playlist) + "\n" + getString(R.string.playlist_description))

        txtRunning.formatText()
        txtMyLibrary.formatText()
        txtPlaylist.formatText()

        rbtnRunning.text = txtRunning
        rbtnMyLibrary.text = txtMyLibrary
        rbtnPlaylist.text = txtPlaylist

        val sp = getSharedPreferences(MUSIC_SOURCE_PREF, MODE_PRIVATE)
        musicSource = sp.getInt(MUSIC_SOURCE_KEY, SPOTIFY_RUNNING)
        selectedPlaylist = sp.getString(PLAYLIST_KEY, null)

        (sourceRadioGroup.getChildAt(musicSource) as RadioButton).isChecked = true
        if (musicSource == PLAYLIST) playlistRadioGroup.visibility = View.VISIBLE

        //Add radio buttons for playlists from code
        addPlaylistRadioButtons()

        //If loading of playlists is slower than 300ms, show loading text
        Handler().postDelayed({
            layoutLoading.visibility = View.VISIBLE
        }, 300)

        sourceRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            //Show or hide playlists
            playlistRadioGroup.visibility =
                if (checkedId == R.id.rbtnPlaylist) View.VISIBLE else View.GONE

            //Enable or disable Save button
            if (checkedId != sourceRadioGroup.getChildAt(musicSource).id) btnSave.enable()
            else btnSave.disable()
        }

        playlistRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedRadioButton: View = playlistRadioGroup.findViewById(checkedId)
            val selectedPlaylistIndex = playlistRadioGroup.indexOfChild(selectedRadioButton)

            if (selectedPlaylist != playlistIDs[selectedPlaylistIndex] ||
                sourceRadioGroup.checkedRadioButtonId != sourceRadioGroup.getChildAt(musicSource).id
            ) {

                btnSave.enable()

            } else btnSave.disable()
        }

        btnSave.setOnClickListener {
            saveMusicSource()
            if (musicSource == PLAYLIST) saveSelectedPlaylist()

            btnSave.disable()
            finish()
        }
    }

    private fun saveMusicSource() {
        musicSource = when (sourceRadioGroup.checkedRadioButtonId) {
            R.id.rbtnRunning -> SPOTIFY_RUNNING
            R.id.rbtnMyLibrary -> MY_LIBRARY
            R.id.rbtnPlaylist -> PLAYLIST
            else -> SPOTIFY_RUNNING
        }

        val spSource: SharedPreferences = getSharedPreferences(MUSIC_SOURCE_PREF, MODE_PRIVATE)
        val editor: SharedPreferences.Editor = spSource.edit()
        editor.putInt(MUSIC_SOURCE_KEY, musicSource)
        editor.apply()
    }

    private fun saveSelectedPlaylist() {
        val radioButtonID = playlistRadioGroup.checkedRadioButtonId
        val radioButton: View = playlistRadioGroup.findViewById(radioButtonID)
        val index = playlistRadioGroup.indexOfChild(radioButton)

        selectedPlaylist = playlistIDs[index]

        val spPlaylist: SharedPreferences = getSharedPreferences(MUSIC_SOURCE_PREF, MODE_PRIVATE)
        val playlistEditor: SharedPreferences.Editor = spPlaylist.edit()
        playlistEditor.putString(PLAYLIST_KEY, selectedPlaylist)
        playlistEditor.apply()
    }

    override fun onBackPressed() {
        if (btnSave.isEnabled) {

            val alertDialog: AlertDialog = this.let {
                val builder = AlertDialog.Builder(it)
                builder.apply {
                    setTitle(getString(R.string.unsaved_changes))
                    setMessage(getString(R.string.want_to_save_now))

                    setPositiveButton(getString(R.string.yes)) { _, _ ->
                        btnSave.performClick()

                        super.onBackPressed()
                    }
                    setNegativeButton(getString(R.string.no)) { _, _ ->
                        super.onBackPressed()
                    }
                }
                builder.create()
            }
            alertDialog.show()

        } else {
            super.onBackPressed()
        }
    }

    private fun addPlaylistRadioButtons() {
        MainActivity.webSpotify?.getMyPlaylists(object : Callback<Pager<PlaylistSimple>> {

            override fun success(t: Pager<PlaylistSimple>, response: Response) {
                val playlists = t.items

                if (playlists.isEmpty()) {
                    handleNoSavedPlaylists()
                    return
                }

                (playlistRadioGroup as ViewGroup).removeView(layoutLoading)

                playlists.forEach {
                    playlistIDs.add(it.id)

                    val radioButton = RadioButton(this@MusicSourceActivity)

                    radioButton.text = it.name
                    radioButton.id = View.generateViewId()
                    radioButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16F)

                    val height =
                        TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            40F,
                            resources.displayMetrics
                        ).toInt()

                    playlistRadioGroup.addView(
                        radioButton,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
                    )
                }

                if (selectedPlaylist != null) {
                    val selectedPlaylistIndex: Int = playlistIDs.indexOf(selectedPlaylist!!)

                    val selectedRadioButton: RadioButton? =
                        (playlistRadioGroup.getChildAt(selectedPlaylistIndex) as RadioButton)

                    if (selectedRadioButton != null) {
                        selectedRadioButton.checkWithoutAnimation()
                    } else {
                        selectedPlaylist = playlistIDs[0]
                        (playlistRadioGroup.getChildAt(0) as RadioButton).checkWithoutAnimation()
                    }
                } else {
                    selectedPlaylist = playlistIDs[0]
                    (playlistRadioGroup.getChildAt(0) as RadioButton).checkWithoutAnimation()
                }
            }

            override fun failure(error: RetrofitError) {
                Log.d("playlist", error.message.toString())

                tvLoading.text = getString(R.string.unable_to_load_playlists)
                prBarPlaylists.visibility = View.GONE
            }
        })
    }

    private fun handleNoSavedPlaylists() {
        if (musicSource == PLAYLIST) {
            musicSource = MY_LIBRARY
            (sourceRadioGroup.getChildAt(MY_LIBRARY) as RadioButton).isChecked = true
            saveMusicSource()
        }
        sourceRadioGroup.removeView(rbtnPlaylist)
    }

    //Formats the String from the second line (after \n)
    private fun SpannableString.formatText() {
        val from = this.lines()[0].length

        this.setSpan(RelativeSizeSpan(0.8F), from, this.length, 0)
        this.setSpan(
            ForegroundColorSpan(
                ContextCompat.getColor(
                    this@MusicSourceActivity,
                    R.color.colorPrimary
                )
            ),
            from,
            this.length,
            0
        )
    }

}
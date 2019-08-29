package hu.csomorbalazs.runbeat

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v7.app.AppCompatActivity
import android.support.v7.preference.Preference
import android.support.v7.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        const val KEY_REMOVE_ACCOUNT = "remove_account"
        const val KEY_ABOUT = "about"
        const val KEY_SOURCE = "source_of_songs"
        const val KEY_FEEDBACK = "send_feedback"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, MySettingsFragment())
            .commit()
    }

    override fun onStart() {
        super.onStart()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(this)

        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {

    }

    class MySettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference(KEY_FEEDBACK).onPreferenceClickListener =
                Preference.OnPreferenceClickListener {

                    val email = getString(R.string.feedback_email)
                    val subject = getString(R.string.email_subject)

                    val mailto = "mailto:${Uri.encode(email)}?subject=${Uri.encode(subject)}"

                    val emailIntent = Intent(Intent.ACTION_SENDTO)
                    emailIntent.data = Uri.parse(mailto)

                    startActivity(emailIntent)

                    true
                }
        }
    }

}
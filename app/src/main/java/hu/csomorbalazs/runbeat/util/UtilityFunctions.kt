package hu.csomorbalazs.runbeat.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri

fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager: ConnectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetworkInfo = connectivityManager.activeNetworkInfo

    return activeNetworkInfo != null && activeNetworkInfo.isConnected
}

fun Context.openWebsite(url: String) {
    startActivity(
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse(url)
        )
    )
}
package hu.csomorbalazs.runbeat

class Constants {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_AUTO_DETECT_ON = "ACTION_AUTO_DETECT_ON"
        const val ACTION_AUTO_DETECT_OFF = "ACTION_AUTO_DETECT_OFF"
        const val ACTION_UPDATE_MUSIC_SOURCE = "ACTION_UPDATE_MUSIC_SOURCE"
        const val ACTION_UPDATE_CURRENT_BPM = "ACTION_UPDATE_CURRENT_BPM"
        const val ACTION_BROADCAST_STATE = "ACTION_BROADCAST_STATE "
        const val ACTION_BROADCAST_BPM = "ACTION_BROADCAST_BPM"
        const val ACTION_UPDATE_SPOTIFY = "ACTION_UPDATE_SPOTIFY"

        var serviceRunning = false
    }
}
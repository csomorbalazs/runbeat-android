package hu.csomorbalazs.runbeat.model

class MyTrack(var id: String, var tempo: Float) {

    var uri: String = "spotify:track:$id"
}
package hu.csomorbalazs.runbeat

import android.widget.Button
import android.widget.RadioButton

fun Button.enable() {
    this.alpha = 1F
    this.isEnabled = true
}

fun Button.disable() {
    this.alpha = 0.6F
    this.isEnabled = false
}

fun RadioButton.checkWithoutAnimation() {
    this.isChecked = true
    this.jumpDrawablesToCurrentState()
}

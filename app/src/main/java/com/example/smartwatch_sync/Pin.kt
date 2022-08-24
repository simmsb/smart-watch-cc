package com.example.smartwatch_sync

import android.widget.RadioButton
import android.widget.TextView
import com.example.smartwatchsync.*

data class Pin(val pin: Pins, val name: String, val mainActivity: MainActivity) {
    var mode: PinOperation = PinOperation.SetLow
    lateinit var radios: List<RadioButton>
    lateinit var result: TextView

    fun setResult(reading: Float) {
        val reading = "%.2f".format(reading)
        result.text = "${reading}v"
    }

    fun onClickHandlerFor(button: RadioButton, operation: PinOperation) {
        for (b in radios) {
            if (button != b) {
                b.isChecked = false
            }
        }

        if (operation == PinOperation.SetHigh || operation == PinOperation.SetLow) {
            val msg = message {
                origin = 3387062
                setPin = SetPin.newBuilder()
                    .setPin(pin)
                    .setOp(operation)
                    .build()
            }

            mainActivity.sendMessage(msg)
        }

        mode = operation
    }
}
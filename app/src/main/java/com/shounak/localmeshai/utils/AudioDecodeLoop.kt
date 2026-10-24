package com.shounak.localmeshai.utils

/** Input EOS only stops feeding; delayed decoder output must still be drained to output EOS. */
internal object AudioDecodeLoop {
    fun run(checkActive: () -> Unit, feedInput: () -> Boolean, drainOutput: () -> Boolean) {
        var inputEnded = false
        while (true) {
            checkActive()
            if (!inputEnded) inputEnded = feedInput()
            if (drainOutput()) return
        }
    }
}

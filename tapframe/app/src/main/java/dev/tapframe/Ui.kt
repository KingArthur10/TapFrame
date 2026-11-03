package dev.tapframe

import android.view.View
import android.view.WindowInsets

/** Android 15+ draws edge-to-edge: keep [root]'s content clear of the status and navigation bars. */
fun padForSystemBars(root: View, pad: Int) {
    root.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
        insets
    }
}

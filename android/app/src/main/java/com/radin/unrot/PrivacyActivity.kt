package com.radin.unrot

import android.app.Activity
import android.os.Bundle

/** Health Connect requires this screen explaining what the health data is used for. */
class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen { root ->
            root.add(text("How Unrot uses your workouts", 26f, bold = true))
            root.add(
                text(
                    "Unrot reads your exercise sessions (start time, end time, and type) from Health Connect " +
                        "so it can turn gym and soccer time into scroll time. Nothing else is read. The data never " +
                        "leaves your phone; only your total minutes are synced if you turn on laptop sync.",
                    16f, Col.MUTED,
                ), 20,
            )
            root.add(button("Got it", Ui.Style.PRIMARY) { finish() })
        }
    }
}

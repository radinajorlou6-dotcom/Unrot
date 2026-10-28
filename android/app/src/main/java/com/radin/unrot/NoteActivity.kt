package com.radin.unrot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/** Brain dump or "one thing for tomorrow". Get it out of your head so you can sleep. */
class NoteActivity : Activity() {
    companion object {
        const val EXTRA_TYPE = "type"
        const val TYPE_DUMP = "dump"
        const val TYPE_TOMORROW = "tomorrow"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Store.get(this)
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_DUMP
        val dump = type == TYPE_DUMP

        setScreen { root ->
            root.add(space(16))
            root.addWrap(heroIcon(if (dump) R.drawable.ic_psychology else R.drawable.ic_edit_note, Col.INDIGO), 12)
            root.add(title(if (dump) "What's keeping you up?" else "One thing for tomorrow", 28f), 8)
            root.add(
                text(
                    if (dump) "Write it all down. It'll be here in the morning, so your brain can stop holding it."
                    else "What's the first thing you'll do tomorrow? Unrot will show it to you in the morning.",
                    16f, Col.MUTED,
                ), 18,
            )
            val box = input(if (dump) "Just start typing…" else "e.g. Start the assignment before lunch", multiline = dump)
            root.add(box, 16)
            root.add(button("Save and go to sleep", Ui.Style.PRIMARY, R.drawable.ic_bedtime) {
                val t = box.text.toString().trim()
                if (t.isNotEmpty()) {
                    s.addNote(type, t)
                    s.log(if (dump) "Brain-dumped instead of scrolling" else "Planned tomorrow instead of scrolling")
                }
                Toast.makeText(this@NoteActivity, "Saved. Goodnight 🌙", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            })
            root.add(button("Cancel", Ui.Style.GHOST) { finish() })
            box.requestFocus()
            box.post {
                getSystemService(InputMethodManager::class.java).showSoftInput(box, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}

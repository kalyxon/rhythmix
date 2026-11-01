package com.rhythmix.app.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView

object UiMotion {

    private const val PREFS_NAME = "rhythmix_prefs"
    private const val PREF_REDUCE_MOTION = "reduce_motion"

    fun isReduced(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_REDUCE_MOTION, false)

    fun enter(view: View) {
        if (isReduced(view.context)) return
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 10
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240)
            .start()
    }

    fun animateList(recyclerView: RecyclerView) {
        if (isReduced(recyclerView.context)) return
        recyclerView.layoutAnimation =
            android.view.animation.AnimationUtils.loadLayoutAnimation(
                recyclerView.context,
                com.rhythmix.app.R.anim.layout_fade_up
            )
    }
}

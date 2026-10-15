package com.quranroots.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object WebSearch {

    fun openMeaning(context: Context, term: String) {
        val q = URLEncoder.encode("معنى $term في اللغة العربية", StandardCharsets.UTF_8.name())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openLisanArab(context: Context, term: String) {
        val q = URLEncoder.encode(term, StandardCharsets.UTF_8.name())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.almaany.com/ar/dict/ar-ar/$q/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

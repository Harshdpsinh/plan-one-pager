package com.gohil.bookkeeper

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class BookkeeperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // PDFBox needs its font and encoding resources unpacked before first use; without
        // this, text extraction fails on any PDF with a non-trivial font.
        PDFBoxResourceLoader.init(applicationContext)
    }
}

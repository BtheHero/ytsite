package com.framestack.android

import android.app.Application
import com.framestack.android.data.DownloaderImpl
import org.schabi.newpipe.extractor.NewPipe

class FrameStackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(DownloaderImpl.getInstance())
    }
}

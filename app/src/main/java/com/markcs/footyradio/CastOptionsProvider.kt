package com.markcs.footyradio

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.CastMediaControlIntent

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            // Use the standard Default Media Receiver for HLS audio streams.
            // APP_ID_DEFAULT_RECEIVER_WITH_DRM requires specific LoadRequestData formatting
            // and rejects plain HLS audio (application/vnd.apple.mpegurl), causing the
            // CastPlayer to stall permanently in BUFFERING state.
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
package com.openking.compressclip

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread

interface CompressionListener {
    @MainThread
    fun onStart(index: Int){}

    @MainThread
    fun onSuccess(index: Int, size: Long, path: String?){}

    @MainThread
    fun onFailure(index: Int, failureMessage: String){}

    @WorkerThread
    fun onProgress(index: Int, percent: Float){}

    @WorkerThread
    fun onCancelled(index: Int){}
}

interface CompressionProgressListener {
    fun onProgressChanged(index: Int, percent: Float)
    fun onProgressCancelled(index: Int)
}

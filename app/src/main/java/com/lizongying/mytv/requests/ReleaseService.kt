package com.lizongying.mytv.requests

import retrofit2.Call
import retrofit2.http.GET

interface ReleaseService {
    @GET("version.json")
    fun getRelease(
    ): Call<ReleaseResponse>
}
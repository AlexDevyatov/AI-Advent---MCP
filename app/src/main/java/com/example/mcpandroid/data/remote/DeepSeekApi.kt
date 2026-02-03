package com.example.mcpandroid.data.remote

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface DeepSeekApi {
    @POST("chat/completions")
    @Headers("Content-Type: application/json")
    suspend fun chat(@Body body: RequestBody): ResponseBody
}

package com.example.mcpandroid.data.remote

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST

interface McpApi {
    @POST("mcp")
    suspend fun mcp(@Body body: RequestBody): ResponseBody
}

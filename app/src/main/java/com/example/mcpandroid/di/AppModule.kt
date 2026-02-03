package com.example.mcpandroid.di

import android.content.Context
import com.example.mcpandroid.data.local.ReminderDao
import com.example.mcpandroid.data.local.RemindersDatabase
import com.example.mcpandroid.data.remote.DeepSeekApi
import com.example.mcpandroid.data.remote.McpApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideRemindersDatabase(@ApplicationContext context: Context): RemindersDatabase {
        return RemindersDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideReminderDao(db: RemindersDatabase): ReminderDao = db.reminderDao()

    @Provides
    @Singleton
    @Named("mcpBaseUrl")
    fun provideMcpBaseUrl(): String = "http://192.168.1.138:8000/"

    @Provides
    @Singleton
    @Named("mcp")
    fun provideMcpRetrofit(@Named("mcpBaseUrl") baseUrl: String): Retrofit {
        // FIX: readTimeout — не зависать при долгом ответе MCP (таймаут подпроцесса в прокси ~30 сек)
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(35, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build()
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideMcpApi(@Named("mcp") retrofit: Retrofit): McpApi = retrofit.create(McpApi::class.java)

    @Provides
    @Singleton
    @Named("deepSeekApiKey")
    fun provideDeepSeekApiKey(@ApplicationContext context: Context): String {
        return try {
            val firstLine = context.assets.open("creds.txt").bufferedReader().use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: ""
            }
            // Поддержка формата KEY=value или только ключ
            if (firstLine.contains("=")) firstLine.substringAfter("=").trim() else firstLine
        } catch (_: Exception) {
            ""
        }
    }

    @Provides
    @Singleton
    @Named("deepseek")
    fun provideDeepSeekRetrofit(@Named("deepSeekApiKey") apiKey: String): Retrofit {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(client)
            .build()
    }

    @Provides
    @Singleton
    fun provideDeepSeekApi(@Named("deepseek") retrofit: Retrofit): DeepSeekApi = retrofit.create(DeepSeekApi::class.java)
}

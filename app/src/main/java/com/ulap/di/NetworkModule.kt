package com.ulap.di

import com.ulap.data.googlephotos.GooglePhotosPickerApi
import com.ulap.data.remote.TelegramBotApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.ulap.data.auth.GoogleAuthManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.NONE
                }
            )
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.telegram.org/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideTelegramBotApi(retrofit: Retrofit): TelegramBotApi =
        retrofit.create(TelegramBotApi::class.java)

    @Provides
    @Singleton
    fun provideGooglePhotosPickerApi(
        okHttpClient: OkHttpClient,
        googleAuthManager: GoogleAuthManager,
    ): GooglePhotosPickerApi {
        val client = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val token = googleAuthManager.getAccessToken()
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://photospicker.googleapis.com/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GooglePhotosPickerApi::class.java)
    }

    // NOTE: @UploadClient on the parameter is mandatory.
    // Without it, Hilt injects the default OkHttpClient silently — no compile error.
    @Provides
    @Singleton
    @UploadClient
    fun provideUploadOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)   // 5 min — at 0.5 Mbps, 19MB = 304s
            .writeTimeout(300, TimeUnit.SECONDS)  // 5 min — same rationale for write
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    @UploadClient
    fun provideUploadRetrofit(@UploadClient uploadClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.telegram.org/")
            .client(uploadClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    @UploadClient
    fun provideUploadTelegramBotApi(@UploadClient retrofit: Retrofit): TelegramBotApi =
        retrofit.create(TelegramBotApi::class.java)
}

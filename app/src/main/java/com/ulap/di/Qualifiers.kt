package com.ulap.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UploadClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainPrefs

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

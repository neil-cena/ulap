package com.ulap.di

import com.ulap.data.repository.CredentialRepositoryImpl
import com.ulap.data.repository.FolderRepositoryImpl
import com.ulap.data.repository.MediaRepositoryImpl
import com.ulap.domain.repository.CredentialRepository
import com.ulap.domain.repository.FolderRepository
import com.ulap.domain.repository.MediaRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    @Binds
    @Singleton
    abstract fun bindFolderRepository(impl: FolderRepositoryImpl): FolderRepository

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(impl: CredentialRepositoryImpl): CredentialRepository
}

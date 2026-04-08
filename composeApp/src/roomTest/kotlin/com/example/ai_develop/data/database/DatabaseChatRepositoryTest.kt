package com.example.ai_develop.data.database

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // Указываем SDK для Robolectric
class DatabaseChatRepositoryTest : AbstractDatabaseChatRepositoryTest()

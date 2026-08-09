package music.ai.recommend.db

interface AppDatabase {
    fun musicDao(): MusicDao
}

expect fun getAppDatabase(): AppDatabase
